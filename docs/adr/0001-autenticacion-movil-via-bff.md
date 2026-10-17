# ADR 0001 – Autenticación de la app móvil a través del frontoffice de ciudadanos (BFF)

- **Estado:** reemplazada por [ADR 0003](0003-login-movil-directo-gubuy.md) (la app va directo a gub.uy y al backend)
- **Fecha:** 2026-10-12
- **Alcance:** `TSE_MOVIL` (app del chofer) y `TSE_CENTRAL/componente_central/frontend/frontofficeciudadanos`

## Contexto

La letra pide que el chofer se autentique en el componente móvil con su usuario gub.uy (ID Uruguay).
El frontoffice de ciudadanos ya tiene un login OpenID Connect contra el ambiente de testing de ID
Uruguay: el servidor de Next canjea el `code`, valida el `id_token`, llama al backend
(`/ciudadanos/ingreso-gubuy`) para crear o validar el perfil contra la PDI y obtiene los roles
(`RESPONSABLE`, `CHOFER` con estado).

Restricciones del cliente que tenemos registrado en ID Uruguay:

1. **Es un cliente confidencial.** El canje del `code` exige `client_id` + `client_secret` (HTTP Basic).
   Un secreto embebido en el APK se puede extraer descompilando la app: quedaría comprometido el
   cliente de todo el sistema, también el de la web.
2. **Solo acepta volver a `http://localhost` y `http://localhost:8080`.** No hay una redirect URI propia
   de la app (esquema `cargauy://` o App Link https), así que gub.uy no puede devolver el control
   directamente a la app.
3. **El central igual tiene que validar la identidad** y aplicar las mismas reglas que la web (perfil,
   validación PDI, mayoría de edad, roles). La letra pide además que el móvil consuma servicios del
   componente central.

## Decisión

La app **no habla con gub.uy ni con WildFly directamente**. Usa el servidor de Next del frontoffice de
ciudadanos como **BFF** (backend for frontend):

1. La app genera un `code_verifier` (PKCE) y abre en un **Custom Tab** (navegador del sistema, nunca un
   WebView) `/auth/movil/login?redirect=cargauy://auth&code_challenge=…&code_challenge_method=S256`.
   Solo se acepta ese `redirect` (lista blanca).
2. Next arranca **el mismo login OIDC que la web** (misma redirect URI `http://localhost`), marcando en
   la cookie cifrada de la transacción que el flujo es móvil.
3. En el callback, después del ingreso exitoso y solo si la persona es `CHOFER` `HABILITADO`, Next no
   crea la sesión web: emite un **código de un solo uso** (60 s, atado al `code_challenge`) y vuelve a
   `cargauy://auth?code=…` (o `?error=no_chofer|cancelado|…`).
4. La app canjea `{code, codeVerifier}` en `POST /api/movil/token` y recibe un **JWT HS256** firmado
   con `MOVIL_TOKEN_SECRET` (`sub` = cédula, nombre, roles, vence a las 8 h). El token nunca viaja en
   una URL y se guarda en `EncryptedSharedPreferences`.
5. Las llamadas de la app (`/api/movil/viajes`, `/api/movil/eventos`) llevan `Authorization: Bearer`.
   Next valida el token (401 `token_vencido` / `token_invalido`) y llama al backend con `X-Api-Key`,
   `X-Usuario-Ci` (la cédula del token) y `X-Correlation-Id`.

## Consecuencias

**A favor**
- Se reusa el login existente: una sola integración con ID Uruguay, las mismas validaciones (PDI,
  roles) y los mismos logs con `correlationId`.
- El `client_secret` y la `BACKEND_API_KEY` quedan solo en el servidor.
- Cumple las recomendaciones para apps nativas (RFC 8252): navegador del sistema y PKCE.
- El backend Java no cambia su modelo de confianza: sigue recibiendo `X-Usuario-Ci` solo desde Next.

**En contra / riesgos**
- En desarrollo, la vuelta a `http://localhost` exige que el `localhost` del dispositivo sea la PC:
  `adb reverse tcp:80 tcp:80`, que en el emulador requiere `adb root` (imagen *Google APIs*). En un
  teléfono real sin root habría que usar la otra redirect URI (`http://localhost:8080`).
- Los códigos de un solo uso viven en memoria del proceso de Next: con varias instancias del front
  habría que moverlos a un almacenamiento compartido.
- No hay refresh token: a las 8 h el chofer vuelve a ingresar (los eventos pendientes se conservan y se
  envían después).

## Alternativas descartadas

- **App directo contra gub.uy (AppAuth) con el cliente actual:** obliga a embeber el `client_secret` y
  no hay redirect URI de la app registrada.
- **WebView dentro de la app:** desaconsejado por RFC 8252 y por ID Uruguay (la app podría capturar las
  credenciales).
- **Para producción:** pedir a ID Uruguay un **cliente público para la app** (PKCE + redirect propia
  `cargauy://` o App Link). Aun así se mantendría que el central emita su propio token a la app.
