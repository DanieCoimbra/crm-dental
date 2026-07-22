<?php

namespace App\Http\Controllers;

use App\Models\WhatsAppLog;
use Illuminate\Http\Request;

class WhatsAppLogController extends Controller
{
    public function index()
    {
        // Apenas gerentes podem ver todos os logs
        if (auth()->user()->role !== 'manager') {
            return response()->json(['message' => 'Acesso negado.'], 403);
        }

        $logs = WhatsAppLog::with('patient')->orderBy('created_at', 'desc')->get();
        return response()->json($logs);
    }
}
