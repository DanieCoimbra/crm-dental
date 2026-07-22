<?php

namespace App\Imports;

use App\Models\Patient;
use Illuminate\Support\Collection;
use Maatwebsite\Excel\Concerns\ToCollection;
use Maatwebsite\Excel\Concerns\WithHeadingRow;
use Illuminate\Support\Facades\Log;
use Carbon\Carbon;
use PhpOffice\PhpSpreadsheet\Shared\Date;

class PatientsImport implements ToCollection, WithHeadingRow
{
    public function collection(Collection $rows)
    {
        foreach ($rows as $row) {
            $name = $this->findAlias($row, ['nome', 'paciente', 'nome_completo', 'name', 'nome_do_paciente']);
            
            if (!$name) {
                continue;
            }

            $email = $this->findAlias($row, ['email', 'e_mail', 'correio']);
            $phone = $this->findAlias($row, ['telefone', 'fone', 'celular', 'whatsapp', 'contato']);
            $cpf = $this->findAlias($row, ['cpf', 'documento', 'doc']);
            $dob = $this->findAlias($row, ['data_de_nascimento', 'nascimento', 'data_nascimento', 'dt_nasc']);

            $parsedDob = null;
            if ($dob) {
                try {
                    if (is_numeric($dob)) {
                        $parsedDob = Date::excelToDateTimeObject($dob)->format('Y-m-d');
                    } else {
                        // Assuming string format like DD/MM/YYYY
                        $parsedDob = Carbon::createFromFormat('d/m/Y', str_replace('-', '/', $dob))->format('Y-m-d');
                    }
                } catch (\Exception $e) {
                    try {
                        $parsedDob = Carbon::parse($dob)->format('Y-m-d');
                    } catch (\Exception $e2) {
                        $parsedDob = null;
                    }
                }
            }

            Patient::create([
                'name' => $name,
                'email' => $email,
                'phone' => $phone,
                'cpf' => $cpf,
                'date_of_birth' => $parsedDob,
            ]);
        }
    }

    private function findAlias($row, array $aliases)
    {
        foreach ($aliases as $alias) {
            if (isset($row[$alias])) {
                return $row[$alias];
            }
        }
        return null;
    }
}
