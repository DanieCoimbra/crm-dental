# Contract — F03 Criptografia de campos

## 1. Resumo
- **Feature:** F03-field-encryption
- **Versão:** 1.0.0
- **Consumidores:** módulos `record` (Fase 1), `documents` (Fase 4) e qualquer módulo com campo sensível. Contrato interno, sem endpoints HTTP.

## 2. Entradas

### 2.1 Variáveis de ambiente
| Variável | Formato | Regra |
|---|---|---|
| `CRM_AES_KEYS` | `<versão>:<base64 de 32 bytes>[,<versão>:<base64>...]` | Versão inteira 1–255; sem repetição |
| `CRM_AES_ACTIVE_VERSION` | inteiro | Deve existir em `CRM_AES_KEYS` |

### 2.2 Interface Java (`crm_data.shared.crypto`)
```text
String AesGcmCipher.encrypt(String plain)   // null -> null
String AesGcmCipher.decrypt(String stored)  // null -> null; CryptoException em falha
String AesGcmCipher.rotate(String stored)   // reescreve com a chave ativa
class  EncryptedStringConverter implements AttributeConverter<String,String>
```
Uso: `@Convert(converter = EncryptedStringConverter.class)` sobre um campo `String`, com coluna `text`.

## 3. Saídas

- **Formato armazenado:** `base64( versão[1 byte] | IV[12 bytes] | cifrado + tag[16 bytes] )`.
- **Erros:** `CryptoException` (não verificada), com `reason` em `{KEY_INVALID, VERSION_UNKNOWN, TAMPERED, MALFORMED}`; nunca contém texto claro nem chave.
- Quando propagada por um endpoint, vira 500 `internal` (formato da F01).

## 4. Regras e limites
- Algoritmo fixo: `AES/GCM/NoPadding`, chave de 256 bits, tag de 128 bits.
- IV aleatório e único por operação; proibido reutilizar.
- Chave inválida ou ausente impede o startup.
- Campo cifrado não pode ser usado em `WHERE`, `ORDER BY` ou índice.
- Tamanho recomendado por campo: ≤ 64 KB de texto claro.
- Texto claro, chaves e valores cifrados não vão para logs.
- Remover uma versão de chave do chaveiro só depois de nenhum dado a usar.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `decrypt(encrypt(x)) == x` para texto vazio, ASCII, acentuado e emoji.
- [ ] Duas chamadas `encrypt(x)` retornam valores diferentes.
- [ ] Alterar 1 byte do valor armazenado gera `CryptoException(TAMPERED)`.
- [ ] Dado cifrado com a versão 1 é lido quando a ativa é a 2; `rotate` o regrava com a 2.
- [ ] Chave de 16 bytes ou versão ativa inexistente impede o startup.
- [ ] `null` é gravado e lido como `null` via `EncryptedStringConverter` (teste com PostgreSQL real).
- [ ] 10 KB cifram e decifram em ≤ 5 ms.
