# CSAE POS Android

App nativa Android (Kotlin + Jetpack Compose) para el Punto de Venta del casino.

## Sprint 3.0 (actual) - MOCK FIRST

Esta version usa **datos en memoria** (sin backend, sin auth real, sin BD).
El objetivo es iterar la UX del POS antes de gastar tiempo en integracion.

### Como probar (build local)

1. Abrir el proyecto en Android Studio (`File > Open` -> seleccionar este directorio).
2. Sincronizar Gradle (el wrapper se incluye).
3. Conectar una tablet Android o emulador (min SDK 26 / Android 8+).
4. Run > Run 'app'.

### Credenciales demo

| Usuario  | Password   | Rol         |
|----------|-----------|-------------|
| operador | demo123   | OperadorPOS |
| admin    | admin123  | AdminCasino |

### RUTs mock para probar

| RUT         | Nombre            | Servicios disponibles |
|-------------|-------------------|------------------------|
| 12345678-5  | Juan Perez        | Almuerzo, Desayuno     |
| 11111111-1  | Maria Gonzalez    | Almuerzo               |
| 22222222-2  | Pedro Ramirez     | Cena, Colacion         |
| 12345678-5  | Ana Silva         | Almuerzo, Cena         |
| 12345678-5  | Luis Morales      | Desayuno, Colacion     |

### Pantallas (4)

1. **Login** (`/login`): usuario + password hardcoded.
2. **Dashboard**: KPIs del dia + lista de ultimos tickets + boton grande "GENERAR TICKET".
3. **POS**: buscar comensal por RUT -> seleccionar servicio -> generar ticket.
4. **Ticket**: preview del ticket generado + botones Imprimir / Nuevo.

### Estructura del proyecto

```
csae-pos-android/
  app/
    src/main/
      AndroidManifest.xml
      java/cl/csae/pos/
        MainActivity.kt
        model/         (Comensal, Servicio, Ticket, UsuarioPos, Kpi)
        data/          (MockRepository - 100% en memoria)
        ui/
          theme/       (Color, Theme, Type)
          screens/     (Login, Dashboard, POS, Ticket)
          AppNavHost.kt
      res/            (themes, colors, strings, icons)
    build.gradle.kts
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradle/wrapper/    (Gradle 8.14.5)
```

## Sprint 3.1 (proximamente)

- Conexion a `CSAE.Api` con Retrofit + JWT (login real).
- SQLite local con Room para cache offline (sync incremental).
- Impresion ESC/POS Bluetooth (libreria generica).
- Modo kiosko real (lock task mode via DevicePolicyManager).
- Multi-casino (selector de restaurante).
