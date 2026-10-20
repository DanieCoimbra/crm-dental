# PRD — F03 Criptografia de campos (AES-256-GCM)

**Derivação:** PRD global, FR-8 e riscos 4; `docs/arq_inicial.md` (campos sensíveis do prontuário).
**Dependências externas:** F01 (variáveis de ambiente e validação de startup). Não depende de F02 nem de F04.

## 1. Introdução

- **Problema:** o prontuário contém dados de saúde (sensíveis pela LGPD); um vazamento do banco não pode expor o conteúdo.
- **Solução:** componente reutilizável que cifra e decifra campos de texto em nível de aplicação com AES-256-GCM e chaveiro versionado, pronto para uso pelas fases 1+.
- **Metas:**
  1. 100% dos valores cifrados são irrecuperáveis sem a chave (teste: adulteração de 1 byte falha na decifragem).
  2. Dois cifrados do mesmo texto nunca são iguais (IV aleatório).
  3. Rotação de chave sem indisponibilidade: dados antigos continuam legíveis.
  4. Overhead de cifrar/decifrar um campo de 10 KB ≤ 5 ms.

## 2. User stories

### US-001: Cifrar e decifrar campos
**Descrição:** Como módulo de prontuário, quero marcar um campo como sensível e tê-lo cifrado de forma transparente.

**Critérios de aceite:**
- [ ] Um conversor JPA aplicado ao campo grava o valor cifrado no banco e devolve o texto claro na leitura.
- [ ] `null` permanece `null`; texto vazio é suportado.
- [ ] Valor cifrado adulterado gera erro, nunca texto incorreto.

### US-002: Chaveiro versionado
**Descrição:** Como operador, quero rotacionar a chave sem perder dados antigos.

**Critérios de aceite:**
- [ ] `CRM_AES_KEYS` aceita várias versões; `CRM_AES_ACTIVE_VERSION` define qual cifra novos dados.
- [ ] Dado cifrado com a versão 1 continua legível quando a ativa é a 2.
- [ ] Versão desconhecida no dado gera erro claro.

### US-003: Falha no startup
**Descrição:** Como operador, quero que chave ausente ou inválida impeça a aplicação de subir.

**Critérios de aceite:**
- [ ] Chave com tamanho diferente de 32 bytes, base64 inválido ou versão ativa inexistente abortam o startup, sem imprimir a chave.

## 3. Requisitos funcionais

- **FR-1:** Algoritmo `AES/GCM/NoPadding`, chave de 256 bits, IV de 12 bytes aleatório por operação, tag de 128 bits.
- **FR-2:** Formato armazenado: `base64( versão[1] | IV[12] | texto cifrado + tag[16] )`.
- **FR-3:** O chaveiro deve ser carregado de `CRM_AES_KEYS` (`versão:base64`, separadas por vírgula) e `CRM_AES_ACTIVE_VERSION`.
- **FR-4:** Deve existir um conversor JPA reutilizável (`EncryptedStringConverter`).
- **FR-5:** Deve existir um utilitário de recriptografia que reescreve um valor com a chave ativa (para rotação gradual).
- **FR-6:** Texto claro e chaves nunca devem aparecer em logs.

## 4. Fora do escopo

- Busca/ordenação sobre campos cifrados (campos de busca ficam em claro).
- Cifra de arquivos/exames (decisão da Fase 1).
- Gestão da chave em cofre (KMS); nesta fase a chave vem de variável de ambiente.
- A tarefa de recriptografia em lote (cada módulo a agenda quando precisar).

## 5. Considerações técnicas e riscos

- Perder a chave significa perder os dados; exigir backup seguro das chaves fora do repositório e do banco.
- Chaves em variável de ambiente ficam visíveis ao operador da plataforma; migrar para um cofre é evolução futura.
- Limite de uso seguro do GCM por chave (volume de mensagens): irrelevante para o porte do produto, mas a rotação periódica é recomendada.
- **Pergunta aberta:** nenhuma.
