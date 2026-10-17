# ADR 0002 – Resolución de conflictos entre el móvil sin conexión y el componente central

- **Estado:** aceptada
- **Fecha:** 2026-10-12
- **Alcance:** app del chofer (`TSE_MOVIL`), `ViajesChoferService` / `MovilResource` / `GuiasService`
  (backend Frontofficeciudadanos) y la página `/empresa/guias/[id]` (frontoffice de ciudadanos)

## Contexto

La letra (punto 4.d) pide definir y documentar una política para cuando el mismo viaje se modifica
desde el frontoffice mientras el dispositivo está sin conexión. La app registra los eventos del viaje
(inicio, cargas y descargas parciales, incidentes, fin) offline y los envía después, en orden y con un
UUID generado en el teléfono (idempotencia por clave primaria, punto 4.c). Mientras tanto, en el
central puede pasar que:

- la empresa edite datos de la guía (destino, carga, vehículo…);
- la empresa reasigne la guía a otro chofer o la cancele;
- otro teléfono del mismo chofer ya haya registrado lo mismo (p. ej. el inicio);
- el evento ya no tenga sentido (p. ej. una carga después del fin).

"Gana el último que escribe" no sirve: los eventos llegan tarde (con hora del dispositivo vieja) y
pisarían cambios de la empresa, o al revés, se perderían hechos que ocurrieron en el viaje.

## Decisión

**Propiedad por tipo de dato:**

- Los **datos de la guía** pertenecen a la **empresa** (frontoffice). Ante una diferencia **gana el
  central**; el teléfono se actualiza y avisa al chofer.
- El **avance del viaje** pertenece al **chofer**. Los eventos son **hechos ocurridos**: el central no
  los descarta en silencio. Si son aplicables cambian el estado; si no, **se registran igual como
  conflicto** sin tocar la guía, para que el responsable los vea.

Cada evento recibido con datos válidos se guarda en `eventos_viaje` con un **resultado**:

| Resultado | Cuándo | Guía | HTTP | Estado en el teléfono |
|---|---|---|---|---|
| `APLICADO` | La transición es válida (INICIO en ASIGNADA, FIN en EN_CURSO, cargas/descargas/incidentes en EN_CURSO) | Cambia (si corresponde) | 200 | `SINCRONIZADO` |
| `SIN_EFECTO` | El efecto ya estaba aplicado: INICIO con la guía EN_CURSO, FIN con la guía FINALIZADA | No cambia | 200 | `SINCRONIZADO` con nota |
| `CONFLICTO` | No aplicable: `VIAJE_NO_INICIADO`, `VIAJE_FINALIZADO`, `GUIA_NO_ASIGNADA`, `GUIA_REASIGNADA`, `GUIA_CANCELADA` | No cambia | 409 | `CONFLICTO` (no se reintenta) |

Los errores que no son hechos válidos (datos inválidos 400, no es chofer 403, guía inexistente o de otra
empresa 404, `idEvento` usado por otra persona 409) **no se guardan** y el teléfono los marca
`RECHAZADO`.

**Detección de cambios de la empresa:** el teléfono manda con cada evento la `versionGuia` que conoce
(la de la entidad `Guia`, `@Version`). Si no coincide con la del central antes de aplicar el evento, la
respuesta trae `guiaModificada: true`. Todas las respuestas incluyen la guía actual (salvo que ya no
sea del chofer): el teléfono la guarda y, si cambió algún dato de la empresa, muestra *"La empresa
modificó esta guía"*. El refresco de la lista hace la misma comparación.

**Orden y horas:** se guardan la hora del dispositivo y la de recepción. El teléfono envía en orden de
registro y se detiene ante un error transitorio (no manda un FIN antes que su INICIO). El historial del
responsable se ordena por hora del dispositivo y luego por recepción.

**Concurrencia:** el registro bloquea la fila de la guía (`PESSIMISTIC_WRITE`) y después busca el
`idEvento`. Dos envíos simultáneos del mismo evento: el segundo espera y obtiene el resultado original.
Dos eventos distintos sobre la misma guía: el segundo ve el estado que dejó el primero (p. ej. el
segundo INICIO queda `SIN_EFECTO`).

**Visibilidad:** el responsable ve en `/empresa/guias/{id}` todos los eventos (con foto y ubicación),
junto con las modificaciones de la empresa en una sola línea de tiempo, con los `CONFLICTO` marcados y un aviso arriba.

## Consecuencias

**A favor**
- No se pierden hechos del viaje: todo lo que el chofer registró queda en el central, aplicado o no.
- Los cambios de la empresa nunca se pisan desde el móvil.
- El reintento es seguro: idempotencia por `idEvento` y respuestas repetibles (incluso los conflictos).
- Reglas en un solo lugar del servidor (`ViajesChoferService.decidir`), con un test por caso.

**En contra**
- Los conflictos requieren revisión humana: no se resuelven solos (p. ej. una carga informada después
  de cerrar el viaje queda registrada pero no reabre la guía).
- Depende del reloj del teléfono para el orden de los hechos.

## Modificaciones desde el frontoffice (activas)

El responsable puede **editar, reasignar o cancelar** una guía mientras está `ASIGNADA` (el chofer
todavía no la inició), desde `/empresa/guias/{id}`, con control de versión (409 `GUIA_DESACTUALIZADA`
si la guía cambió mientras la editaba) y registro de cada cambio en la línea de tiempo de la guía
(`modificaciones_guia`). Una guía `EN_CURSO`, `FINALIZADA` o `CANCELADA` no se puede modificar
(409 `GUIA_NO_MODIFICABLE`), lo que evita conflictos sobre viajes ya empezados. Efecto sobre el móvil:

| Acción del responsable | Evento del chofer que estaba sin conexión |
|---|---|
| Editar datos | Se aplica; la respuesta trae `guiaModificada: true` y los datos nuevos (caso 1) |
| Reasignar a otro chofer | `CONFLICTO` / `GUIA_REASIGNADA` (caso 2) |
| Cancelar | `CONFLICTO` / `GUIA_CANCELADA`; la guía deja de venir en `GET /api/movil/viajes` (caso 2) |

Detalle de reglas y endpoints: sección *Modificacion de guias* del README de `TSE_CENTRAL`.

## Pendiente

- Notificar al responsable cuando llega un conflicto (hoy lo ve al abrir la guía).

## Alternativas descartadas

- **Last-write-wins por hora del dispositivo:** los eventos offline pisarían cambios de la empresa y
  depende de relojes no confiables.
- **Rechazar (y descartar) todo evento que no aplique:** se pierden hechos reales del viaje (p. ej. un
  incidente informado tarde).
- **Bloquear la guía mientras el chofer está sin conexión:** no se sabe cuándo vuelve la conexión y
  dejaría a la empresa sin poder operar.
