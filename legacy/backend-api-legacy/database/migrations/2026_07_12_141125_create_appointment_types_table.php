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
        Schema::create('appointment_types', function (Blueprint $table) {
            $table->id();
            $table->string('name');
            $table->integer('duration_minutes');
            $table->string('color')->default('#3b82f6');
            $table->timestamps();
            $table->softDeletes();
        });

        // Limpar appointments pois a estrutura antiga do tipo vai quebrar
        DB::table('appointments')->truncate();

        Schema::table('appointments', function (Blueprint $table) {
            $table->dropColumn('type');
            $table->foreignId('appointment_type_id')->constrained('appointment_types')->onDelete('cascade');
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::table('appointments', function (Blueprint $table) {
            $table->dropForeign(['appointment_type_id']);
            $table->dropColumn('appointment_type_id');
            $table->string('type')->default('consultation');
        });

        Schema::dropIfExists('appointment_types');
    }
};
