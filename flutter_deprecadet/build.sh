#!/bin/bash
set -e

echo "=== Iniciando processo de Build do Flutter Web na Vercel ==="

# Instalar/Atualizar o Flutter SDK com clone raso (--depth 1) para maior velocidade
if [ -d "flutter" ]; then
  echo "=> Atualizando Flutter SDK local..."
  cd flutter && git pull && cd ..
else
  echo "=> Clonando Flutter SDK (branch stable)..."
  git clone https://github.com/flutter/flutter.git -b stable --depth 1
fi

# Adicionar Flutter ao PATH temporário da build
export PATH="$PATH:$(pwd)/flutter/bin"

echo "=> Verificando instalação do Flutter:"
flutter --version

echo "=> Baixando dependências do projeto (pub get)..."
flutter pub get

echo "=> Compilando versão Web (Release)..."
flutter build web --release --dart-define=API_BASE_URL=https://crm-dental-lap6.onrender.com

echo "=== Build concluída com sucesso! ==="
