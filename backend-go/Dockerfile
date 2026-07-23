# Stage 1: Build binary
FROM golang:alpine AS builder

WORKDIR /app

# Instala dependências necessárias para o build
RUN apk add --no-cache git ca-certificates tzdata

# Copia arquivos de dependência
COPY go.mod go.sum ./
RUN go mod download

# Copia todo o código-fonte do backend
COPY . .

# Compila o binário otimizado e estático
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-w -s" -o server ./cmd/server

# Stage 2: Imagem final ultra-leve
FROM alpine:latest

WORKDIR /app

RUN apk add --no-cache ca-certificates tzdata

# Copia o binário gerado do estágio builder
COPY --from=builder /app/server .

# Expõe a porta padrão (Render sobrescreverá a porta via variável de ambiente PORT)
EXPOSE 8080

# Comando para iniciar o servidor
CMD ["./server"]
