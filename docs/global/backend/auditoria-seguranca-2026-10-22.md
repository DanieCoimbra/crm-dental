# Auditoria de segurança e desempenho (2026-10-22): decisões

Origem: relatório do `qa-reviewer` sobre `back-java` e `database` (nenhum achado Crítico ou Alto; 2 Médio e 5 Baixo).

## Implementado

| # | Achado | O que mudou |
|---|---|---|
| 2 | BCrypt caro sem limite global | `HashingGate` (login e registro): no máximo `max(2, CPUs)` hashes simultâneos, 3 s de espera e depois 429. Novo limite de 20 falhas/15 min por e-mail, de qualquer IP (`LoginRateLimiter`). Contrato F04 atualizado. |
| 5 | `entries.clear()` zerava os limites | `IdleEviction`: descarta ociosos e, no teto, só os 10% menos recentes. Usado por `LoginRateLimiter` e `UploadRateLimiter`. A ociosidade passou de 10 para 20 min (maior que a janela de 15 min do e-mail). |
| 1 | Teste de performance da busca | `database/tests/performance_search.sql` mede também as consultas exatas do backend (`LIKE '%q%' ESCAPE`, `active`, `ORDER BY name, id`, `LIMIT` e o `count(*)`). |

## Não implementado, com motivo

- **#1, trocar `%q%` por prefixo para 1–2 caracteres:** a premissa da auditoria estava errada. Sob RLS o `LIKE` não é *leakproof*, então nem o índice trigram nem o de prefixo são usados (F02 PRD, seção 5). A consulta varre só as linhas da clínica pelo índice `(clinic_id, name, id)`. Medido (10 mil pacientes na clínica, 100 mil no banco): 2 caracteres com `%q%` = 6,6 ms; prefixo = 5,0 ms; 3+ caracteres = 5,4 ms; `count(*)` = 5,3 ms, todos sem Seq Scan. Trocar para prefixo mudaria o resultado da busca e o contrato sem ganho relevante.
- **#1, `Page` → `Slice`:** o contrato da F02 devolve `totalElements` e `totalPages`; o `count(*)` custa ≈ 5 ms. Mantido.
- **#3 token de acesso após desativação (≤ 15 min):** aceito. Reduzir o TTL ou manter lista de usuários desativados fica para produção real.
- **#4 registro público (enumeração por 409 e criação ilimitada de clínicas, só limitada por IP):** aceito no portfólio. Em produção: confirmação de e-mail com resposta genérica e CAPTCHA.
- **#6 CPF na query string:** já aceito no F02 PRD (alternativa: `POST /patients/search`). Verificar o log do proxy da hospedagem.
- **#7 AES-GCM sem AAD:** endurecimento futuro; exige migração dos dados cifrados (vincular `clinic_id` e `patient_id`).

## 2ª passada (mesmo dia)

| # | Achado | O que mudou |
|---|---|---|
| 1 (Médio) | O limite de 20 falhas/15 min por e-mail permitia a um anônimo travar a conta do dono | O limite por e-mail só soma IPs que nunca entraram na conta. IP com login bem-sucedido nos últimos 7 dias fica fora (`LoginRateLimiter.knownLocations`, em memória); o limite de 5 falhas/min por IP+e-mail continua valendo para ele. Contrato F04 atualizado. |
| 2 (Baixo) | BCrypt dentro de transação e fora do `HashingGate` | `UserAdminService.createReceptionist/resetPassword` e `ProfileService.changePassword/updateProfile` calculam o hash antes da transação, dentro do portão; a gravação é uma transação curta com checagem da `version` do usuário. |
| 3 (Baixo) | Trocar o e-mail sem confirmar a senha | `PUT /me` exige `currentPassword` quando o e-mail muda (400 sem ela, 401 errada, limite de 5/min compartilhado com a troca de senha) e revoga as sessões. **Muda o contrato da F05** (campo novo, opcional); o front precisa enviar a senha ao trocar o e-mail. |
| 4 (Baixo) | Stack completa no log do handler genérico | Se a cadeia de causas tem `SQLException`, o log leva só classe e SQLState; sem causa SQL, a stack continua para diagnóstico. |

Limite conhecido: o estado de "IP conhecido" se perde no restart (como os demais limites), e quem entra de um IP novo durante um ataque àquela conta espera até 15 min.
