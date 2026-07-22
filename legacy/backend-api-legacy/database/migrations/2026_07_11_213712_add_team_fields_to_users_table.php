<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('cpf')->nullable()->unique();
            $table->string('phone')->nullable();
            $table->string('address')->nullable();
            $table->string('medical_registry')->nullable(); // CRM
            $table->string('ctps')->nullable(); // Carteira de Trabalho
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn(['cpf', 'phone', 'address', 'medical_registry', 'ctps']);
        });
    }
};
