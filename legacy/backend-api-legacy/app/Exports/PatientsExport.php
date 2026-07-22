<?php

namespace App\Exports;

use App\Models\Patient;
use Maatwebsite\Excel\Concerns\FromCollection;
use Maatwebsite\Excel\Concerns\WithHeadings;
use Maatwebsite\Excel\Concerns\WithMapping;

class PatientsExport implements FromCollection, WithHeadings, WithMapping
{
    public function collection()
    {
        return Patient::all();
    }

    public function headings(): array
    {
        return [
            'ID',
            'Nome',
            'E-mail',
            'Telefone',
            'Data de Nascimento',
            'CPF',
            'Observações',
            'Data de Cadastro'
        ];
    }

    public function map($patient): array
    {
        return [
            $patient->id,
            $patient->name,
            $patient->email,
            $patient->phone,
            $patient->date_of_birth ? $patient->date_of_birth->format('d/m/Y') : '',
            $patient->cpf,
            $patient->notes,
            $patient->created_at->format('d/m/Y H:i')
        ];
    }
}
