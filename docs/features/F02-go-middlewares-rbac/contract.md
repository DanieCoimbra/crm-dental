# Contrato de API: Middlewares & Role Security

## 1. Resumo do Contrato
- **Domínio:** `F02-go-middlewares-rbac`
- **Versão:** 1.0.0
- **Consumidores:** Handlers de API internos, Frontend

## 2. Respostas de Erro de Segurança Padronizadas

### 2.1 Erro 401 Unauthorized (Token Ausente ou Inválido)
```json
{
  "error": "UNAUTHORIZED",
  "message": "Token de autenticação ausente ou expirado",
  "details": null
}
```

### 2.2 Erro 403 Forbidden (Acesso Negado por RBAC)
```json
{
  "error": "FORBIDDEN",
  "message": "Acesso negado: o perfil 'receptionist' não possui permissão para esta rota",
  "details": null
}
```
