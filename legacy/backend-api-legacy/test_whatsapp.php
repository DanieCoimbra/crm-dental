<?php

use App\Models\Patient;
use App\Models\Appointment;
use Carbon\Carbon;

// Paciente de Aniversário
$p1 = Patient::updateOrCreate(['email' => 'niver@teste.com'], [
    'name' => 'João Aniversariante',
    'cpf' => '11122233344',
    'phone' => '11999998888',
    'birth_date' => now()->toDateString(),
]);

// Paciente com Consulta Amanhã
$p2 = Patient::updateOrCreate(['email' => 'consulta@teste.com'], [
    'name' => 'Maria da Consulta',
    'cpf' => '55566677788',
    'phone' => '11977776666',
    'birth_date' => now()->subYears(30)->toDateString(),
]);

// Try to find a user and appointment type to prevent foreign key errors
$user = \App\Models\User::first();
$type = \App\Models\AppointmentType::first();

if ($user && $type) {
    Appointment::updateOrCreate(['patient_id' => $p2->id], [
        'doctor_id' => $user->id,
        'appointment_type_id' => $type->id,
        'start_time' => now()->addDay()->setHour(14)->setMinute(30)->toDateTimeString(),
        'end_time' => now()->addDay()->setHour(15)->setMinute(0)->toDateTimeString(),
    ]);

    // Paciente para Recuperação (Cancelou há 30 dias)
    $p3 = Patient::updateOrCreate(['email' => 'recupera@teste.com'], [
        'name' => 'Carlos Cancelado',
        'cpf' => '99988877766',
        'phone' => '11955554444',
        'birth_date' => now()->subYears(40)->toDateString(),
    ]);

    $app3 = Appointment::updateOrCreate(['patient_id' => $p3->id], [
        'doctor_id' => $user->id,
        'appointment_type_id' => $type->id,
        'start_time' => now()->subDays(30)->setHour(10)->toDateTimeString(),
        'end_time' => now()->subDays(30)->setHour(11)->toDateTimeString(),
    ]);
    $app3->delete(); // Soft Delete simulating cancellation
    
    echo "Dados de teste gerados com sucesso.\n";
} else {
    echo "Falta user ou appointment type no banco.\n";
}
