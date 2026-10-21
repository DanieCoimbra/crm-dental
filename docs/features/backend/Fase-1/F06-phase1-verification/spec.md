# Spec — F06 Verificação da fase

Complementa `docs/global/backend/Fase-1/spec.md` (seção 8). Define a suíte transversal; não cria código de produção.

## 1. Visão técnica
- **Feature:** F06 Verificação da fase.
- **Stack:** JUnit 5, Mockito, `@WebMvcTest` + `spring-security-test`, Testcontainers PostgreSQL (`DatabaseContainer` existente), `RecordingStatementInspector`, captura de log (Logback `ListAppender`).
- **Abordagem:** classes novas em `src/test/java/crm_data/` (pacote `phase1`) reutilizando `support/` da Fase 0; `*IT` pelo `maven-failsafe-plugin`.

## 2. Dados
- Banco descartável criado pelos scripts de `database/bootstrap` e `database/migrations` (V001–V013).
- **Fixtures** (`support/phase1/`): duas clínicas (A, B) com dentista e recepcionista cada; pacientes, histórico, notas e exames; gerador de 10 mil pacientes; função que recua `updated_at`/`noted_at` pela conexão do dono para vencer a retenção.
- **Estado em memória:** nenhum.

## 3. Suítes
| Classe | Tipo | Cobertura |
|---|---|---|
| `Phase1RoleMatrixWebTest` | `@WebMvcTest` | 16 rotas × {401, R, D}; DTOs ignoram `clinicId`/`createdBy`/`role`/`id`; `no-store`; formato `ProblemDetail` |
| `Phase1SessionVariablesIT` | IT | Instrução única com 3 variáveis; pool de 1 conexão sem vazamento; fluxo `TenantRunner` com `''` |
| `Phase1TenantIsolationIT` | IT | Filtro desligado; leitura cruzada vazia nas 5 tabelas (JPA e SQL nativo); `{id}` de outra clínica → 404 |
| `Phase1ForgedRoleIT` | IT | `app.role = RECEPTIONIST` forjado e ausente/inválido; `app.user_id` ausente (`28000`); R anexa exame sem `SELECT`/`RETURNING` |
| `Phase1EncryptionAtRestIT` | IT | SQL nativo em `content`; regex Base64; texto original ausente |
| `Phase1LogHygieneIT` | IT | Captura de log em CPF duplicado, falha de decifra, falha de Storage, download e anonimização; asserção de ausência de dados sensíveis |
| `Phase1SearchPerformanceIT` | IT | 10 mil pacientes, 4 concorrentes, p95 ≤ 100 ms (`-Dperf.limit-ms`); timeline com 500 notas |
| `Phase1UploadPerformanceIT` | IT | 10 MB com `LocalFileStorage` ≤ 5 s; sem leitura integral em memória |
| `Phase1SchemaValidateIT` | IT | Contexto sobe com `ddl-auto=validate` contra V001–V013 e as 5 entidades |
| `Phase1ArchitectureTest` | Unitário | `record` não importa repositório de `patient` ou `auth`; só `TenantHibernateJpaDialect` escreve `app.*`; entidades novas estendem `TenantAwareEntity` (reaproveita `TenantEntityRules`) |

## 4. Lógica
1. Montar o banco com os scripts reais e criar as fixtures.
2. Executar cada suíte contra o contexto Spring completo com `FileStorage` de teste (em memória, com modo de falha injetável).
3. Atualizar os testes da Fase 0 que afirmam o texto da instrução de sessão.
4. Gerar o relatório de rastreabilidade: US-P1-01…11 → classes de teste.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Docker indisponível | ITs falham com mensagem clara; testes unitários e web seguem |
| Medição de desempenho lenta no ambiente | Limite sobrescrevível por propriedade; registrar a máquina no relatório |
| Fixture de retenção alterando dado real | Só em banco descartável, nunca no Supabase |
| Defeito encontrado | Volta à feature dona (F01–F05); a suíte não o contorna |

## 6. Segurança e desempenho
- Dados de teste fictícios; nenhum segredo real.
- A suíte completa de ITs da Fase 1 deve caber em tempo razoável (meta ≤ 10 min local, banco reutilizado entre classes).
