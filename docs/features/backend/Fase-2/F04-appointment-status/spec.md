# Spec — F04 Máquina de status do agendamento

Complementa `docs/global/backend/Fase-2/spec.md` (seções 3.2, 4.7 e 5) e o PRD global (seção 5).

## 1. Visão técnica
- **Feature:** F04 Máquina de status.
- **Stack:** Spring Web MVC, Spring Data JPA, Spring Security (`@PreAuthorize`), `Clock`.
- **Abordagem:** `AppointmentTransitions` (puro) e métodos de transição em `AppointmentService`; rotas novas em `AppointmentController`. Sem migrations.

## 2. Dados
- **Tabela:** `core.appointments` (coluna `status`). Nenhuma alteração de schema.
- **Repositório:** `existsByStatusAndIdNot(IN_PROGRESS, id)` para o único atendimento em andamento.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `AppointmentTransitions` | `Action` (`CONFIRM`, `CANCEL`, `NO_SHOW`, `START`, `FINISH`, `REACTIVATE`) → origens, destino, papéis |
| `AppointmentService` | `confirm`, `cancel`, `noShow` (D, R); `start`, `finish`, `reactivate` (`@PreAuthorize` D) |
| `AppointmentController` (rotas) | `POST /appointments/{id}/{ação}` com `{version}` |
| `SecurityConfig` | `start`, `finish`, `reactivate` só D |
| `SchedulingEvents` | `StatusChanged` |

## 4. Lógica
Tabela (de → para): `SCHEDULED` → `CONFIRMED` (confirm); `SCHEDULED`/`CONFIRMED` → `CANCELED` (cancel); `SCHEDULED`/`CONFIRMED` → `NO_SHOW` (no-show, só com agora ≥ `startsAt`); `CONFIRMED` → `IN_PROGRESS` (start, mesmo dia local, único `IN_PROGRESS`); `IN_PROGRESS` → `COMPLETED` (finish); `CANCELED`/`NO_SHOW` → `SCHEDULED` (reactivate, sujeito a conflito e paciente).

Algoritmo: papel → carregar no tenant (404) → `version` do corpo ≠ entidade (409) → status fora das origens (409 com o status atual) → regra extra → atribuir destino e `flush`. Em `reactivate`, `23P01`, `CR005` e `CR003` viram 409; nas demais ações, 0 linhas ou `OptimisticLockException` viram 409. Publica `StatusChanged` e responde 200 com o recurso.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Recepção em `start`/`finish`/`reactivate` | 403 antes de abrir transação |
| Recepção sobre `IN_PROGRESS`/`COMPLETED`/`CANCELED`/`NO_SHOW` | 409 pela checagem prévia; papel forjado no banco: 0 linhas |
| Transição fora da tabela | 409 com o status atual |
| `version` defasada | 409 |
| `no-show` antes do horário | 422 `NOT_STARTED` |
| `start` fora do dia local | 422 `NOT_TODAY` |
| Outro `IN_PROGRESS` na clínica | 409 "Já existe um atendimento em andamento." |
| Reativar sobre horário ocupado / paciente inativo / anonimizado | 409 |
| `42501` num `UPDATE` de status da recepção | 500 `internal`, log de erro (defeito do backend) |

## 6. Segurança e desempenho
- Papel só do JWT; rota e `@PreAuthorize` negam antes do banco; a RLS é a segunda barreira.
- Log só com `appointmentId`, status de origem e destino.
- Uma leitura e uma gravação por transição; `start` faz uma consulta extra de existência.
