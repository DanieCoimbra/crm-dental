# Spec — F03 Criptografia de campos

Complementa `docs/global/backend/spec.md` (seção 4.7).

## 1. Visão técnica
- **Feature:** F03 Criptografia de campos.
- **Stack:** `javax.crypto` (JCA) com `AES/GCM/NoPadding`, `SecureRandom`, JPA `AttributeConverter`.
- **Abordagem:** módulo `crm_data.shared.crypto` sem dependência de outras features; stateless (o chaveiro é carregado uma vez).

## 2. Dados e estado
- **Banco:** sem tabelas. Colunas que usarem o conversor serão `text` e guardarão o valor base64.
- **Estado em memória:** `KeyRing` imutável: mapa `versão (1–255) → chave de 32 bytes` e a versão ativa.
- **Configuração:** `CRM_AES_KEYS` = `1:<base64>,2:<base64>`; `CRM_AES_ACTIVE_VERSION` = inteiro presente no mapa.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `KeyRing` | Carrega e valida as chaves; expõe `active()` e `byVersion(v)` |
| `AesGcmCipher` | `encrypt(String) → String` e `decrypt(String) → String`; reencripta com `rotate(String)` |
| `EncryptedStringConverter` | `AttributeConverter<String,String>` usado nas entidades |
| `CryptoConfig` | Cria o `KeyRing` como singleton no startup (`KeyRing.parse`): chaveiro ausente ou inválido aborta a aplicação em qualquer perfil, sem imprimir a chave |

## 4. Lógica
- **encrypt:** `null` → `null`; gera IV de 12 bytes (`SecureRandom`); cifra o UTF-8 com a chave ativa; concatena `versão | IV | cifrado+tag`; base64.
- **decrypt:** `null` → `null`; decodifica; lê o byte de versão; busca a chave; separa IV e cifrado; decifra e valida a tag. Falha de tag lança `CryptoException`.
- **rotate:** `decrypt` e `encrypt` com a chave ativa; se a versão já for a ativa, devolve o mesmo valor.
- **Dados autenticados adicionais (AAD):** não usados nesta versão (o conversor JPA não conhece a linha); evolução futura. Quando houver AAD, incluir também o byte de versão e o `clinic_id`/id da linha, para amarrar o valor à sua linha (impede trocar valores cifrados entre linhas do mesmo tenant).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Chave ≠ 32 bytes ou base64 inválido | Startup aborta; mensagem cita a versão, nunca a chave |
| Versão ativa ausente do chaveiro | Startup aborta |
| Valor adulterado/truncado | `CryptoException`; o módulo chamador devolve 500 `internal` sem detalhe |
| Versão desconhecida no dado | `CryptoException` com a versão (sem conteúdo) |
| Texto vazio | Cifrado normalmente |
| Campos muito grandes | Limite de tamanho por campo definido pelo módulo dono (recomendado ≤ 64 KB) |

## 6. Segurança e desempenho
- IV nunca reutilizado: sempre aleatório; nunca derivado do conteúdo.
- Texto claro e chaves fora de logs e de mensagens de exceção.
- Meta: ≤ 5 ms para 10 KB; coberta por teste de desempenho simples.
- Chaves só por variável de ambiente; sem valores padrão.
