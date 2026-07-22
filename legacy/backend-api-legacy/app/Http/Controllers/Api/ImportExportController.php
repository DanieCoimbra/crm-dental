<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Maatwebsite\Excel\Facades\Excel;
use App\Exports\PatientsExport;
use App\Imports\PatientsImport;

class ImportExportController extends Controller
{
    public function export()
    {
        return Excel::download(new PatientsExport, 'pacientes.xlsx');
    }

    public function import(Request $request)
    {
        $request->validate([
            'file' => 'required|mimes:xlsx,csv,txt|max:10240',
        ]);

        try {
            Excel::import(new PatientsImport, $request->file('file'));
            return response()->json(['message' => 'Pacientes importados com sucesso!'], 200);
        } catch (\Exception $e) {
            return response()->json(['message' => 'Erro ao importar arquivo. Verifique o formato ou tente usar o modelo padrão.', 'error' => $e->getMessage()], 400);
        }
    }

    public function template()
    {
        $headers = ['Nome', 'Email', 'Telefone', 'CPF', 'Data de Nascimento'];
        
        $csv = implode(',', $headers) . "\n";
        
        return response($csv, 200, [
            'Content-Type' => 'text/csv',
            'Content-Disposition' => 'attachment; filename="modelo-importacao.csv"',
        ]);
    }
}
