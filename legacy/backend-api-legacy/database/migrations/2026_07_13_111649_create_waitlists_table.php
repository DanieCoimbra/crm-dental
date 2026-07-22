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
        Schema::create('waitlists', function (Blueprint $table) {
            $table->id();
            $table->foreignId('patient_id')->constrained()->onDelete('cascade');
            $table->foreignId('doctor_id')->nullable()->constrained('users')->onDelete('set null');
            $table->foreignId('appointment_type_id')->nullable()->constrained()->onDelete('set null');
            $table->json('preferred_days')->nullable();
            $table->string('preferred_time_range')->nullable(); // manhã, tarde, qualquer
            $table->string('urgency_level')->default('low'); // low, medium, high
            $table->text('notes')->nullable();
            $table->string('status')->default('waiting'); // waiting, resolved
            $table->timestamps();
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('waitlists');
    }
};
