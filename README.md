# TSE Móvil – app del chofer (carga.uy)

App Android (Kotlin, Jetpack Compose, minSdk 26, `applicationId` `uy.carga.movil`) para que el chofer
vea sus viajes asignados y registre los eventos del viaje, **aunque no tenga conexión**.

- **Login** directo contra gub.uy ([ADR 0003](docs/adr/0003-login-movil-directo-gubuy.md)):
  botón *Ingresar con gub.uy* → Custom Tab al `authorize` de ID Uruguay (PKCE S256, `state`, `nonce`)
  → gub.uy vuelve a la app por su redirect URI (`uy.carga.movil:/oauth2redirect`, o mientras no esté
  registrada `http://localhost:8080`, que atiende un servidor HTTP mínimo de la app en el teléfono) →
  la app manda el `code` al componente central, `POST /api/movil/auth/token` en WildFly, que lo canjea
  con el `client_secret`, valida al chofer (PDI, roles) y devuelve el token (8 h), que se guarda en
  `EncryptedSharedPreferences`. La app no pasa por el front web. *Salir* borra el token y los datos
  locales (avisa si quedan eventos sin enviar).
- **Mis viajes:** las guías `ASIGNADA` / `EN_CURSO` (rubro, tipo de carga, kg, fecha, origen, destino, vehículo) se guardan en Room (con migraciones, para no perder eventos pendientes al actualizar la app). Con conexión se refrescan del
  central (al abrir, al deslizar hacia abajo y cuando vuelve la red); sin conexión se muestra lo local
  con el aviso *"Sin conexión, datos del dd/mm/aaaa hh:mm"*. Si no hay viajes: *"No tenés viajes pendientes"*.
- **Detalle del viaje** con las acciones que permite el estado: *Iniciar* (asignado), *Carga parcial*,
  *Descarga parcial*, *Incidente* (descripción obligatoria + foto de cámara o galería, achicada a
  1280 px JPEG) y *Finalizar* (en curso). Cada acción genera un UUID, se guarda en Room como
  `PENDIENTE` y cambia el estado local en el momento (optimista). Si el chofer dio permiso de ubicación,
  se adjunta la última ubicación conocida.
- **Sincronización y conflictos:** WorkManager envía los pendientes cuando hay red, en orden y con su
  UUID (el central es idempotente). Cada evento queda `PENDIENTE`, `SINCRONIZADO`, `CONFLICTO` o
  `RECHAZADO`, y el detalle del viaje muestra cada uno con su estado. Ver
  [Operación sin conectividad](#operación-sin-conectividad).

## Estructura

```
app/src/main/java/uy/carga/movil/
├── MainActivity.kt / Contenedor.kt     # actividad unica (recibe uy.carga.movil:/oauth2redirect) e inyeccion manual
├── datos/RepositorioViajes.kt          # fuente de verdad (Room) + refresco + registro + sync
├── datos/local/BaseLocal.kt            # Room: viajes y eventos
├── datos/remoto/ApiMovil.kt            # OkHttp + kotlinx.serialization contra /api/movil/*
├── datos/Fotos.kt                      # fotos pendientes y compresion
├── dominio/Modelo.kt                   # estados, tipos de evento, reglas de transicion
├── sesion/                             # token cifrado, login con gub.uy (PKCE) y ReceptorLoopback
├── sync/SincronizacionEventos.kt       # SyncWorker + programacion en WorkManager
└── ui/                                 # ViewModel y pantallas Compose
app/src/test/                           # tests unitarios (JUnit + fakes de Room/API/WorkManager)
```

`BuildConfig.BASE_URL` es la URL de la API REST del componente central (WildFly,
`frontofficeciudadanos-web`), en `gradle.properties`, propiedad `cargauy.baseUrl`:
`https://env-6537388.web.elasticloud.uy/frontofficeciudadanos-web`, o
`http://10.0.2.2:8080/frontofficeciudadanos-web` para el WildFly local desde el emulador. El cliente
de gub.uy se configura con `cargauy.oidcAuthorize`, `cargauy.oidcClientId`, `cargauy.oidcScopes` y
`cargauy.oidcRedirect` (por defecto testing, `890192` y `http://localhost:8080`; cuando AGESIC
registre la redirect URI de la app, `cargauy.oidcRedirect=uy.carga.movil:/oauth2redirect`). La app solo permite HTTP sin cifrar para
`localhost`, `127.0.0.1` y `10.0.2.2` (`res/xml/network_security_config.xml`).

## Operación sin conectividad

La letra (punto 4) pide que la app sea usable sin cobertura de datos. Así se cumple cada inciso:

| Requisito | Cómo se cumple |
|---|---|
| **4.a** La Guía queda disponible localmente | Cada refresco exitoso guarda las guías en Room (tabla `viajes`). La pantalla **siempre lee de Room**, nunca directo de la red: sin conexión se ve lo último descargado con el aviso *"Sin conexión, datos del dd/mm/aaaa hh:mm"*. |
| **4.b** Los eventos sin conexión se persisten y se sincronizan | Cada acción se guarda primero en Room (tabla `eventos`, estado `PENDIENTE`, foto comprimida en el almacenamiento interno) y el estado del viaje cambia en el momento (optimista). WorkManager los envía cuando hay red. |
| **4.c** Identificador generado en el dispositivo, sin duplicados | Cada evento lleva un UUID generado en el teléfono (`idEvento`). Los reintentos reusan ese mismo id. En el central `idEvento` es la **clave primaria** de `eventos_viaje`: un reenvío devuelve el resultado original (`duplicado: true`) sin volver a aplicarlo; dos envíos simultáneos los resuelve la clave primaria más el bloqueo de la fila de la guía. |
| **4.d** Política de resolución de conflictos | Ver la tabla de abajo y el [ADR 0002](docs/adr/0002-resolucion-de-conflictos.md). |

### Cómo se sincroniza

```mermaid
sequenceDiagram
    actor Chofer
    participant App as App (Room)
    participant WM as WorkManager
    participant WF as WildFly (MovilResource)
    participant DB as PostgreSQL

    Chofer->>App: Iniciar viaje (sin conexión)
    App->>App: UUID + evento PENDIENTE en Room, estado local EN_CURSO
    App->>WM: encolar "sincronizar-eventos" (requiere red)
    Note over WM: espera a que haya red (backoff exponencial si falla)
    WM->>App: sincronizarPendientes()
    loop cada PENDIENTE, en el orden en que se registraron
        App->>WF: POST /api/movil/eventos (Bearer, X-Correlation-Id = idEvento, versionGuia)
        WF->>DB: bloquear guía, ¿existe idEvento?, decidir, guardar evento
        WF-->>App: 200 APLICADO / SIN_EFECTO · 409 CONFLICTO (+ guía actual)
        App->>App: SINCRONIZADO / CONFLICTO / RECHAZADO; actualiza la guía
    end
    App->>WF: GET /api/movil/viajes (al abrir, al deslizar, al volver la red)
    WF-->>App: guías ASIGNADA / EN_CURSO → Room
```

1. **Registro local.** `RepositorioViajes.registrarEvento` valida que la acción esté permitida para el
   estado local, genera el UUID, guarda el evento `PENDIENTE` (con la hora del dispositivo, ISO-8601
   con zona, y la ubicación si hay permiso) y agenda la sincronización.
2. **Agenda.** `ProgramadorWorkManager` encola un trabajo único (`sincronizar-eventos`) con restricción
   `NetworkType.CONNECTED` y backoff exponencial desde 15 s. Al registrar un evento usa `KEEP` (si ya
   hay uno agendado, ese mismo envía todo); al volver la red, al refrescar con pendientes o al tocar
   *Enviar pendientes ahora* usa `REPLACE`, para no esperar el backoff. Un `NetworkCallback` del
   `ConnectivityManager` detecta que volvió la red.
3. **Envío.** `SyncWorker` llama a `sincronizarPendientes()`, que manda los pendientes **de a uno y en
   orden de registro**, con la `versionGuia` que el teléfono conoce en ese momento. Si un envío falla
   por la red o el servidor (sin conexión, timeout, 5xx), se corta ahí para no mandar un FIN antes que
   su INICIO; el worker devuelve `retry` y WorkManager lo reintenta con el mismo `idEvento`.
4. **Resultado** (en el teléfono):
   | Respuesta del central | Estado del evento en el teléfono | ¿Se reintenta? |
   |---|---|---|
   | 200 `APLICADO` | `SINCRONIZADO` | No |
   | 200 `SIN_EFECTO` (el efecto ya estaba aplicado) | `SINCRONIZADO`, con la nota *"Sin cambios en el central: …"* | No |
   | 409 `CONFLICTO` (registrado, no aplicado) | `CONFLICTO`, con el motivo | No |
   | 400 / 403 / 404 / otro 409 (no se guardó) | `RECHAZADO`, con el motivo | No |
   | 401 (token vencido) | sigue `PENDIENTE`; se envía después de volver a ingresar | Sí, tras el login |
   | sin red, timeout, 5xx | sigue `PENDIENTE` (*"reintentando"*) | Sí (backoff) |
5. **Datos de la guía.** Cada respuesta trae la guía actual del central y el teléfono la guarda (gana el
   central). Después, el refresco (`GET /api/movil/viajes`) baja la lista completa: se borran del
   teléfono las guías que ya no vienen (finalizadas, canceladas o reasignadas), **salvo las que tienen
   eventos pendientes**, que se conservan hasta enviarlos.
6. **Estado mostrado.** El viaje muestra el último estado confirmado por el central con los eventos
   `PENDIENTE` aplicados encima (`ReglasViaje.estadoEfectivo`). Si un evento termina en `CONFLICTO` o
   `RECHAZADO`, deja de contar y el estado vuelve al del central.

### Política de resolución de conflictos (4.d)

**Cada dato tiene un dueño** (detalle y alternativas descartadas en el
[ADR 0002](docs/adr/0002-resolucion-de-conflictos.md)):

- Los **datos de la guía** (rubro, tipo de carga, kg, fecha, origen, destino, vehículo, chofer) los
  administra la **empresa** desde el frontoffice: **gana siempre el central**.
- El **avance del viaje** (inicio, cargas, descargas, incidentes, fin) lo informa el **chofer**: son
  hechos ocurridos, así que el central **no los descarta**. Los aplica si son válidos y, si no, los
  registra como `CONFLICTO` para que el responsable los vea.

| # | Situación | Qué hace el central | Qué ve el chofer | Qué ve el responsable (web) |
|---|---|---|---|---|
| 1 | La empresa editó datos de la guía mientras el teléfono estaba sin conexión | Acepta el evento normalmente. Si la `versionGuia` que mandó el teléfono no coincide (`@Version` de `Guia`), responde `guiaModificada: true` con los datos actuales | Se actualizan los datos y aparece *"La empresa modificó esta guía"* (botón *Entendido*). También se detecta al refrescar si cambió algún dato | Historial normal |
| 2 | La guía fue reasignada a otro chofer o cancelada | Guarda el evento como `CONFLICTO` (`GUIA_REASIGNADA` / `GUIA_CANCELADA`), no toca la guía y responde 409 (si fue reasignada, sin los datos de la guía, que ya no son del chofer) | Evento *"En conflicto: La empresa reasignó/canceló la guía"*; el viaje desaparece al refrescar | Evento marcado en conflicto |
| 3 | El efecto ya estaba aplicado (p. ej. INICIO con la guía ya EN_CURSO desde otro teléfono, o FIN ya finalizada) | Guarda el evento como `SIN_EFECTO`, no toca la guía, responde 200 | `SINCRONIZADO`, *"Sin cambios en el central: …"* | Evento *"Sin efecto"* |
| 4 | Evento imposible para el estado actual (p. ej. carga o incidente después de FIN, FIN sin INICIO) | Guarda el evento como `CONFLICTO` (`VIAJE_FINALIZADO`, `VIAJE_NO_INICIADO`), no toca la guía, responde 409 | Evento *"En conflicto"* con el motivo; no se reintenta | Evento marcado en conflicto, con aviso arriba |
| 5 | Mismo evento reenviado (respuesta perdida, reintento) | Detecta el `idEvento` existente y devuelve el resultado original (`duplicado: true`) | Igual que el envío original | Un solo evento |

- **Orden y horas:** cada evento guarda la **hora del dispositivo** (cuándo pasó) y la **hora de
  recepción** en el central. El teléfono envía en orden de registro; el historial de la web se ordena
  por hora del dispositivo y después por recepción.
- **Modificaciones desde el frontoffice (activas):** el responsable puede editar, reasignar o cancelar
  una guía mientras está `ASIGNADA`, con control de versión y registro de cada cambio en la línea de
  tiempo de la guía. Una guía `EN_CURSO` o terminada ya no se modifica, lo que evita conflictos sobre
  viajes empezados. Así los casos 1 y 2 de la tabla se dan en la práctica (ver la sección
  *Modificacion de guias* del README de `TSE_CENTRAL`).
- **Concurrencia en el central:** la fila de la guía se bloquea (`PESSIMISTIC_WRITE`) mientras se
  procesa un evento, así dos eventos simultáneos sobre la misma guía se procesan de a uno, y el segundo
  ve el estado que dejó el primero.
- **Dónde está el código:** `ViajesChoferService.decidir` y `registrarEvento` (backend),
  `RepositorioViajes.sincronizarPendientes` y `aEntidad` (app). Hay un test por cada fila
  (`ViajesChoferServiceTest`, `GuiasServiceTest.historialDeEventos…`, `RepositorioViajesTest`).

### Stack técnico

| Pieza | Tecnología | Para qué |
|---|---|---|
| App | Kotlin 2.0, Jetpack Compose (Material 3), Navigation Compose, minSdk 26 / target 35 | UI |
| Persistencia local | **Room** 2.6 (`viajes`, `eventos`) con migraciones explícitas | Guías y eventos offline (fuente de verdad de la pantalla) |
| Sincronización | **WorkManager** 2.10 (`CoroutineWorker`, trabajo único, `CONNECTED`, backoff exponencial) + `ConnectivityManager.NetworkCallback` | Envío de pendientes cuando hay red |
| HTTP | **OkHttp** 4.12 + **kotlinx.serialization** (sin Retrofit) | Llamadas a `/api/movil/*` |
| Sesión | **EncryptedSharedPreferences** (androidx.security, clave en el Android Keystore) | Token, PKCE verifier, state, nonce, última actualización |
| Login | **Custom Tabs** (androidx.browser) + **PKCE S256** + redirect `uy.carga.movil:/oauth2redirect` o loopback `http://localhost:8080` (`ServerSocket`) | Login directo con gub.uy; el canje lo hace WildFly ([ADR 0003](docs/adr/0003-login-movil-directo-gubuy.md)) |
| Fotos | `BitmapFactory` + `ExifInterface`, JPEG ≤ 1280 px | Incidentes con foto (máx. 5 MB en el central) |
| Backend | Jakarta EE en WildFly 41: `MovilAuthResource` + `LoginMovilService` (login), `MovilTokenFilter` (Bearer), `MovilResource` + `ViajesChoferService`; JPA/Hibernate, PostgreSQL | Login con gub.uy, token **JWT HS256** (8 h, `MOVIL_TOKEN_SECRET`), reglas, idempotencia (`eventos_viaje`, PK = `idEvento`), `@Version` en `Guia` |
| Tests | JUnit 4 + kotlinx-coroutines-test con fakes de Room/API/WorkManager (app); JUnit 5 + H2 (backend) | Un caso por fila de la política |
| Build | Gradle 8.11 (Kotlin DSL), Docker (JDK 17 + Android SDK) | `docker compose up --build apk` |

## Compilar el APK

**Con Docker** (no hace falta Android Studio ni el SDK; la primera vez baja el SDK y las dependencias,
varios minutos). Corre los tests unitarios y deja el APK en `build-output/`:

```bash
docker compose up --build apk
```

Resultado: `build-output/cargauy-chofer-debug.apk`. Para otra URL del servidor:
`CARGAUY_BASE_URL=http://10.0.2.2:8080/frontofficeciudadanos-web docker compose up --build apk`. El build instala el Android SDK
con `sdkmanager`, lo que implica aceptar sus licencias.

**Con Android Studio:** *File → Open* y elegir la carpeta `TSE_MOVIL` (usa el Gradle wrapper 8.11.1 y
el JDK que trae Android Studio; crea solo el `local.properties`). Después de sincronizar, *Run 'app'*
compila e instala en el emulador o teléfono elegido. Desde consola: `./gradlew testDebugUnitTest assembleDebug`
(APK en `app/build/outputs/apk/debug/app-debug.apk`).

## Prueba de punta a punta

### 1. Orden de arranque

1. **PDI** (en `TSE_NODOS`), tiene que estar arriba antes del central:
   ```bash
   docker compose up -d --build pdi
   ```
2. **Central** (en `TSE_CENTRAL`): WildFly (backend + PostgreSQL). La app no necesita el front de
   ciudadanos (sí para el paso 6 del recorrido). El `.env` tiene que tener `OIDC_CLIENT_ID`,
   `OIDC_CLIENT_SECRET` y `MOVIL_TOKEN_SECRET`, que el compose le pasa a WildFly (ver README de `TSE_CENTRAL`).
   ```bash
   docker compose up -d --build wildfly frontofficeciudadanos-front
   ```
   Con el central de elasticloud no hace falta levantar nada: la app usa
   `https://env-6537388.web.elasticloud.uy/frontofficeciudadanos-web`.
   Cada deploy recrea la base y carga `datos-prueba.sql`: la empresa 1, los choferes `56087406` y
   `54074380` habilitados y una guía `ASIGNADA` para cada uno (`GV-MOVIL001` y `GV-MOVIL002`).
   Para volver a empezar la prueba: `docker compose restart wildfly`.
3. **App**: *Run 'app'* desde Android Studio (o instalar el APK con `adb install -r`).

### 2. Emulador o teléfono

Sirve un emulador con Chrome (imagen *Google APIs* o *Google Play*, API 34 o 35) o un teléfono real.
**No hace falta `adb root` ni `adb reverse`:** gub.uy vuelve a la propia app (`http://localhost:8080`
es su servidor loopback en el teléfono, o `uy.carga.movil:/oauth2redirect`), y la app llega a WildFly
por `cargauy.baseUrl` (elasticloud, o `http://10.0.2.2:8080/frontofficeciudadanos-web` para el local
desde el emulador). gub.uy nunca se conecta al teléfono ni al servidor: la vuelta la hace el navegador.

Si quedó un `adb reverse tcp:8080 ...` de antes, sacarlo (`adb reverse --remove-all`): ocuparía el
puerto 8080 del emulador y la app mostraría *"el puerto 8080 del teléfono está en uso"*.

### 3. Recorrido

1. Abrir **carga.uy Chofer** → *Ingresar con gub.uy*. La primera vez Chrome muestra su bienvenida
   (*Use without an account* y aceptar sus condiciones). Ingresar con un usuario de gub.uy testing que
   sea chofer (por ejemplo la cédula `54074380`, que en los datos de prueba es responsable **y** chofer
   de la empresa 1). Al terminar, el navegador vuelve a la app (si no vuelve solo, tocar
   *Volver a la app*). Un usuario que no es chofer habilitado ve *"Tu usuario no está habilitado como chofer…"*.
2. **Mis viajes** muestra `GV-MOVIL002` (o `GV-MOVIL001` para `56087406`) como *Asignado*, con rubro,
   tipo de carga y kg.
3. Activar el **modo avión** (deslizar dos veces desde arriba → *Airplane mode*). Deslizar la lista
   hacia abajo: aparece *"Sin conexión, datos del …"* y la lista se sigue viendo.
4. Entrar al viaje → **Iniciar viaje** → confirmar. El viaje pasa a *En curso* en el teléfono y el
   evento queda *Pendiente*. Se puede registrar también una *Carga parcial* o un *Incidente* con foto.
5. **Desactivar el modo avión.** En unos segundos WorkManager envía los eventos: pasan a *Enviado*.
   (También se puede tocar *Enviar pendientes ahora*.)
6. En la PC, ingresar al front web como responsable (`http://localhost` → *Ingresar con gub.uy* con
   `54074380`) → **Guías** (`/empresa/guias`): la guía figura **En curso**. Después de *Finalizar viaje*
   en la app figura **Finalizada** y deja de aparecer en la app.
7. En **Guías**, tocar el número de la guía: se abre su detalle con el **historial de eventos** que
   mandó la app (hora en que ocurrió y en que llegó, foto del incidente, ubicación). Los eventos en
   conflicto aparecen marcados.
8. Probar la política de conflictos con una guía **Asignada** (antes de iniciarla): con el teléfono sin
   conexión, en la web abrir la guía y usar **Editar** (cambiar el destino), **Reasignar** (a otro chofer)
   o **Cancelar**. Después, en la app, *Iniciar viaje* y volver a conectar: con la edición el viaje se
   inicia y aparece *"La empresa modificó esta guía"*; con la reasignación o la cancelación el evento queda
   *En conflicto* y el viaje desaparece. En la web la línea de tiempo muestra la modificación y el evento.

### Probar la API sin la app

La API del backend (WildFly) se puede probar con `curl` y un token de la app (`$TOKEN`: el que
devuelve `POST /api/movil/auth/token` después de un login; sin token responde 401 `token_invalido`):

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/frontofficeciudadanos-web/api/movil/viajes
```

```bash
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"idEvento":"0d68d4e3-754a-4eed-89bd-c48595c9e92a","idGuia":"GV-MOVIL001","tipo":"INICIO","fechaHora":"2026-10-11T21:00:00-03:00"}' http://localhost:8080/frontofficeciudadanos-web/api/movil/eventos
```

Repetir el segundo comando devuelve el mismo resultado con `"duplicado": true` y la guía no cambia.

## Pendientes y limitaciones conocidas

**Pendiente en el componente central (frontoffice):**
- No hay notificación activa al responsable cuando llega un conflicto: se ve al entrar al detalle de la
  guía (`/empresa/guias/{id}`).

**Desarrollo y pruebas:**
- **Emulador:** si después de usar el modo avión queda sin internet, apagar y prender el Wi-Fi.
- Mientras AGESIC no registre `uy.carga.movil:/oauth2redirect`, el login usa la redirect URI
  `http://localhost:8080` del cliente de testing: si otra app del teléfono ocupa ese puerto, el ingreso
  no arranca. No se verificó si ID Uruguay testing aplica PKCE (el `code_challenge` se manda igual; el
  `nonce` siempre se valida en el servidor).
- Cada arranque de WildFly recrea la base (`drop-and-create` + `datos-prueba.sql`): se pierden los
  eventos, los perfiles y las guías creadas a mano.

**Otras limitaciones:**
- No hay refresh token: a las 8 h hay que volver a ingresar con gub.uy (los eventos registrados
  mientras tanto quedan pendientes y se envían después del nuevo ingreso).
- La hora del evento es la del teléfono: si el reloj del dispositivo está mal, el orden del historial
  también (la hora de recepción del central queda guardada para comparar).
- Falta el logout contra gub.uy (`end_session` con `uy.carga.movil:/logout`): hoy *Salir* solo borra la
  sesión de la app. Se puede agregar cuando AGESIC registre la `post_logout_redirect_uri` (ver
  [ADR 0003](docs/adr/0003-login-movil-directo-gubuy.md)).
- El APK es de debug (firmado con la clave de debug).
