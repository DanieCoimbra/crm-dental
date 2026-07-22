<?php

namespace App\Http\Controllers;

use App\Models\ClinicalEvolution;
use Illuminate\Http\Request;

class ClinicalEvolutionController extends Controller
{
    public function index($patientId)
    {
        $evolutions = ClinicalEvolution::with('user:id,name,role')->where('patient_id', $patientId)->orderBy('created_at', 'desc')->get();
        return response()->json($evolutions);
    }

    public function store(Request $request, $patientId)
    {
        $request->validate([
            'content' => 'required|string',
        ]);

        $evolution = ClinicalEvolution::create([
            'patient_id' => $patientId,
            'user_id' => $request->user()->id,
            'content' => $request->content,
        ]);

        return response()->json($evolution->load('user:id,name,role'), 201);
    }

    public function destroy($id)
    {
        $evolution = ClinicalEvolution::findOrFail($id);
        
        // Apenas o autor ou gerente pode apagar
        if (auth()->user()->role !== 'manager' && auth()->id() !== $evolution->user_id) {
            return response()->json(['message' => 'Acesso negado.'], 403);
        }

        $evolution->delete();
        return response()->json(['message' => 'Evolução excluída.']);
    }
}
