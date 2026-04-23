package org.iesra.revilofe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifySequence

class ExchangeServiceDesignedBatteryTest : DescribeSpec({

    afterTest {
        clearAllMocks()
    }

    describe("Batería de pruebas para ExchangeService basada en clases de equivalencia") {

        describe("A. Validación de entrada") {
            // Usamos un stub básico porque estas validaciones ocurren antes de llamar al proveedor
            val provider = mockk<ExchangeRateProvider>()
            val service = ExchangeService(provider)

            it("1. Debe lanzar excepción si la cantidad es 0") {
                shouldThrow<IllegalArgumentException> {
                    service.exchange(Money(0, "USD"), "EUR")
                }
            }

            it("2. Debe lanzar excepción si la cantidad es negativa") {
                shouldThrow<IllegalArgumentException> {
                    service.exchange(Money(-50, "USD"), "EUR")
                }
            }

            it("3. Debe lanzar excepción si la moneda origen no tiene 3 letras") {
                shouldThrow<IllegalArgumentException> {
                    service.exchange(Money(100, "US"), "EUR") // Origen inválido (2 letras)
                }
            }

            it("4. Debe lanzar excepción si la moneda destino no tiene 3 letras") {
                shouldThrow<IllegalArgumentException> {
                    service.exchange(Money(100, "USD"), "EURO") // Destino inválido (4 letras)
                }
            }
        }

        describe("B. Relación entre moneda origen y destino") {

            it("5. Debe devolver la misma cantidad si origen y destino son iguales") {
                val provider = mockk<ExchangeRateProvider>()
                val service = ExchangeService(provider)

                val result = service.exchange(Money(150, "EUR"), "EUR")

                result shouldBe 150
                // Verificamos que no se ha llamado al proveedor para nada
                verify(exactly = 0) { provider.rate(any()) }
            }

            it("6. Debe convertir correctamente usando una tasa directa con stub") {
                val stubProvider = mockk<ExchangeRateProvider>()
                // Configuramos un STUB simple que devuelva un valor fijo
                every { stubProvider.rate("USDEUR") } returns 0.90

                val service = ExchangeService(stubProvider)

                service.exchange(Money(100, "USD"), "EUR") shouldBe 90
            }

            it("7. Debe usar spy sobre InMemoryExchangeRateProvider para verificar una llamada real correcta") {
                // Instanciamos el objeto real
                val realProvider = InMemoryExchangeRateProvider(mapOf("USDEUR" to 0.90))
                // Creamos el SPY sobre el objeto real
                val spyProvider = spyk(realProvider)
                val service = ExchangeService(spyProvider)

                // Ejecutamos
                service.exchange(Money(100, "USD"), "EUR") shouldBe 90

                // Verificamos la interacción con el spy
                verify(exactly = 1) { spyProvider.rate("USDEUR") }
            }
        }

        describe("C. Estrategia de búsqueda de tasas") {

            it("8. Debe resolver una conversión cruzada cuando la tasa directa no exista usando mock") {
                val mockProvider = mockk<ExchangeRateProvider>()

                // Controlamos el MOCK para simular el fallo directo y el éxito cruzado
                every { mockProvider.rate("USDJPY") } throws IllegalArgumentException()
                every { mockProvider.rate("USDEUR") } returns 0.90
                every { mockProvider.rate("EURJPY") } returns 160.0

                // Limitamos las monedas soportadas a EUR para forzar ese cruce
                val service = ExchangeService(mockProvider, setOf("EUR"))

                // 10 USD * 0.90 = 9 EUR. 9 EUR * 160.0 = 1440 JPY.
                service.exchange(Money(10, "USD"), "JPY") shouldBe 1440L
            }

            it("9. Debe intentar una segunda ruta intermedia si la primera falla usando mock") {
                val mockProvider = mockk<ExchangeRateProvider>()

                // Falla directa
                every { mockProvider.rate("USDJPY") } throws IllegalArgumentException()

                // Falla la primera ruta cruzada (por GBP)
                every { mockProvider.rate("USDGBP") } returns 0.75
                every { mockProvider.rate("GBPJPY") } throws IllegalArgumentException()

                // Éxito en la segunda ruta cruzada (por EUR)
                every { mockProvider.rate("USDEUR") } returns 0.90
                every { mockProvider.rate("EURJPY") } returns 160.0

                // linkedSetOf mantiene el orden: primero intentará GBP, luego EUR
                val service = ExchangeService(mockProvider, linkedSetOf("GBP", "EUR"))

                service.exchange(Money(10, "USD"), "JPY") shouldBe 1440L
            }

            it("10. Debe lanzar excepción si no existe ninguna ruta válida") {
                val mockProvider = mockk<ExchangeRateProvider>()

                // Cualquier llamada lanza excepción
                every { mockProvider.rate(any()) } throws IllegalArgumentException()

                val service = ExchangeService(mockProvider, setOf("EUR", "GBP"))

                shouldThrow<IllegalArgumentException> {
                    service.exchange(Money(10, "USD"), "JPY")
                }
            }

            it("11. Debe verificar el orden exacto de las llamadas al proveedor en una conversión cruzada") {
                val mockProvider = mockk<ExchangeRateProvider>()

                every { mockProvider.rate("USDJPY") } throws IllegalArgumentException() // Intento directo
                every { mockProvider.rate("USDGBP") } returns 0.75                      // Intento GBP paso 1
                every { mockProvider.rate("GBPJPY") } throws IllegalArgumentException() // Intento GBP paso 2 (falla)
                every { mockProvider.rate("USDEUR") } returns 0.90                      // Intento EUR paso 1
                every { mockProvider.rate("EURJPY") } returns 160.0                     // Intento EUR paso 2 (éxito)

                val service = ExchangeService(mockProvider, linkedSetOf("GBP", "EUR"))

                service.exchange(Money(10, "USD"), "JPY")

                // Verificamos rigurosamente el orden de las llamadas (MOCK)
                verifySequence {
                    mockProvider.rate("USDJPY")
                    mockProvider.rate("USDGBP")
                    mockProvider.rate("GBPJPY")
                    mockProvider.rate("USDEUR")
                    mockProvider.rate("EURJPY")
                }
            }
        }
    }
})