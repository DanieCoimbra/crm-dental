<?php

namespace Database\Seeders;

use Illuminate\Database\Seeder;
use App\Models\Patient;

class TrashSeeder extends Seeder
{
    public function run(): void
    {
        for ($i = 41; $i < 50; $i++) {
            Patient::create([
                'name' => 'Paciente Fictício (Lixeira) ' . $i,
                'cpf' => '000.000.000-' . str_pad($i, 2, '0', STR_PAD_LEFT),
                'email' => "lixo$i@teste.com",
                'phone' => '(00) 00000-0000'
            ])->delete();
        }
    }
}
