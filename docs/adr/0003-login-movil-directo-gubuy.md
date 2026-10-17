# ADR 0003 – Login de la app móvil directo contra gub.uy y el componente central

- **Estado:** aceptada (reemplaza a [ADR 0001](0001-autenticacion-movil-via-bff.md))
- **Fecha:** 2026-10-17
- **Alcance:** `TSE_MOVIL` y `TSE_CENTRAL/componente_central/backend/Frontofficeciudadanos` (WildFly)

## Contexto

En ADR 0001 la app usaba el servidor de Next del frontoffice de ciudadanos como BFF: el login con
gub.uy, el token de la app y el proxy a WildFly pasaban por Next. Se pidió que la app **no dependa del
frontoffice de ciudadanos**. La letra además pide:

- que el componente móvil se comunique con el componente central mediante **Web Services REST** y
  por **HTTPS**;
- que la **autorización se haga en la capa de servicios**;
- que el central escale horizontalmente **sin estado local** (los códigos de un solo uso de ADR 0001
  vivían en la memoria de Next);
- que la integración con la **PDI** sea del componente central (SOAP, con timeouts, reintentos y corte).

Restricciones del cliente de ID Uruguay testing (`890192`): es **confidencial** (el canje del `code`
exige el `client_secret`) y hoy solo acepta las redirect URIs `http://localhost` y
`http://localhost:8080`. Se puede pedir a AGESIC que registre redirect URIs nuevas.

## Decisión

La app habla con **dos** sistemas: gub.uy (quién es la persona) y WildFly (qué puede hacer).

```
1. App ──Custom Tab (PKCE, state, nonce)──> gub.uy          el chofer ingresa
2. gub.uy ──redirect con ?code──> App                       uy.carga.movil:/oauth2redirect (o loopback)
3. App ──POST /api/movil/auth/token──> WildFly              { code, codeVerifier, nonce, redirectUri }
4. WildFly ──code + client_secret──> gub.uy /token          el canje: recibe el id_token
5. WildFly: valida id_token (JWKS, iss, aud, exp, nonce) + userinfo (cedula)
6. WildFly: IngresoService (perfil, PDI por SOAP, roles) + exige CHOFER HABILITADO
7. WildFly ──token de la app (JWT HS256, 8 h)──> App
8. App ──Authorization: Bearer──> WildFly /api/movil/viajes y /api/movil/eventos
```

- **Redirect URI de la app:** `uy.carga.movil:/oauth2redirect` (esquema propio, RFC 8252 7.1; no es una
  página pública: Android se la entrega a la app). Mientras AGESIC no la registre se usa
  `http://localhost:8080` con un servidor HTTP mínimo de la app en el teléfono (`ReceptorLoopback`,
  RFC 8252 7.3). Se elige con `cargauy.oidcRedirect` al compilar.
- **El canje lo hace WildFly** (`LoginMovilService`, `ClienteGubUy`, `VerificadorIdToken`): el
  `client_secret` no está en el APK. El `nonce` y el `code_verifier` los genera la app; sin ellos un
  `code` interceptado no sirve.
- **Las reglas de ingreso no se duplican:** se reusa `IngresoService` (el mismo de la web), que consulta
  la PDI. La app nunca habla con la PDI.
- **Token de la app** (`TokenMovil`): JWT HS256 con `MOVIL_TOKEN_SECRET`, `sub` = cédula, 8 h. Sin
  estado en el servidor. `MovilTokenFilter` lo valida en `/api/movil/*` y la cédula sale del token, no
  de un header del cliente. `ViajesChoferService` sigue controlando en cada llamada que sea chofer
  habilitado.
- **El front de Next ya no participa:** se borraron `/auth/movil/login`, `/api/movil/*` y
  `lib/movil/*`. `/api/movil/*` de WildFly no pide `X-Api-Key`.

## Qué pedir a AGESIC

Para el cliente (el actual o, mejor, uno propio de la app):

| Qué | Valor |
|---|---|
| `redirect_uri` | `uy.carga.movil:/oauth2redirect` (si no aceptan ese formato: `uy.carga.movil://oauth2redirect`) |
| `post_logout_redirect_uri` | `uy.carga.movil:/logout` |
| Confirmar | soporte de PKCE (`code_challenge_method=S256`) |

Si no aceptan esquemas propios, se sigue con el loopback `http://localhost:8080`, ya registrado.

## Consecuencias

**A favor**
- La app solo depende de gub.uy y del componente central (REST/HTTPS), como pide la letra.
- La autorización de la API móvil está en WildFly (token firmado), no en la presentación.
- Sin estado en el servidor: cualquier instancia de WildFly atiende el login y valida el token.
- Un solo lugar para el ingreso y la PDI (`IngresoService`), compartido con la web.
- No hace falta `adb root` ni `adb reverse`; funciona con el central local o el de elasticloud.

**En contra / riesgos**
- WildFly necesita `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` y `MOVIL_TOKEN_SECRET` (sin ellos el login
  responde 503 `ingreso_no_disponible`).
- Con el loopback, otra app podría ocupar el puerto 8080 del teléfono (se avisa) y el login no arranca.
- No se verificó que ID Uruguay testing aplique PKCE; el `nonce` se valida siempre.
- `id_token` de testing firmado con RSA de 1024 bits: se acepta solo con el issuer de testing.
- Logout contra gub.uy pendiente: cuando se registre `uy.carga.movil:/logout`, la app abre el
  `end_session` con `id_token_hint` (el central tendría que devolverle el `id_token` en el login).
