package main

import (
	"log"

	"dental-crm-api/internal/database"

	"github.com/joho/godotenv"
)

func main() {
	log.Println("🔄 Iniciando migração do Banco de Dados Supabase...")

	// Carrega variáveis do arquivo .env
	err := godotenv.Load()
	if err != nil {
		log.Println("⚠️ Aviso: Arquivo .env não encontrado. Utilizando variáveis de ambiente do sistema.")
	}

	// Executa conexão e migração automática via GORM
	database.Connect()

	log.Println("🚀 Migração concluída com sucesso no Supabase!")
}
