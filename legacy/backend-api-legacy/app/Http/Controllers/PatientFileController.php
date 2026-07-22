<?php

namespace App\Http\Controllers;

use App\Models\PatientFile;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;

class PatientFileController extends Controller
{
    public function index($patientId)
    {
        $files = PatientFile::where('patient_id', $patientId)->orderBy('created_at', 'desc')->get();
        return response()->json($files);
    }

    public function store(Request $request, $patientId)
    {
        $request->validate([
            'file' => 'required|file|max:10240', // max 10MB
            'category' => 'nullable|string|in:documento,imagem,raio-x,exame,outros',
        ]);

        $file = $request->file('file');
        $originalName = $file->getClientOriginalName();
        $path = $file->store("patients/{$patientId}/files", 'public');

        $patientFile = PatientFile::create([
            'patient_id' => $patientId,
            'file_name' => $originalName,
            'file_path' => $path,
            'file_type' => $file->getMimeType(),
            'category' => $request->input('category', 'documento'),
        ]);

        return response()->json($patientFile, 201);
    }

    public function destroy($id)
    {
        $file = PatientFile::findOrFail($id);
        
        if (auth()->user()->role !== 'manager' && auth()->user()->role !== 'doctor') {
            return response()->json(['message' => 'Acesso negado.'], 403);
        }

        Storage::disk('public')->delete($file->file_path);
        $file->delete();
        
        return response()->json(['message' => 'Arquivo excluído.']);
    }
}
