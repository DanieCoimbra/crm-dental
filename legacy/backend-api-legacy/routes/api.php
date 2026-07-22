<?php

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;
use App\Http\Controllers\RoomController;
use App\Http\Controllers\AppointmentController;
use App\Http\Controllers\AppointmentTypeController;
use App\Http\Controllers\RoleController;

Route::middleware(['auth:sanctum'])->group(function () {
    Route::get('/user', function (Request $request) {
        return $request->user()->load('clinic');
    });

    Route::get('/users', function (Request $request) {
        $query = \App\Models\User::query()->with(['roles', 'currentRoom']);
        if ($request->has('role')) {
            $query->role($request->role);
        }
        return response()->json($query->get());
    });

    Route::apiResource('rooms', RoomController::class);
    Route::post('/shift-assignments', [RoomController::class, 'assign']);

    Route::get('/appointments', [AppointmentController::class, 'index']);
    Route::post('/appointments', [AppointmentController::class, 'store']);
    Route::put('/appointments/{appointment}', [AppointmentController::class, 'update']);
    Route::delete('/appointments/{appointment}', [AppointmentController::class, 'destroy']);
    Route::post('/appointments/{appointment}/start', [AppointmentController::class, 'start']);
    Route::post('/appointments/{appointment}/finish', [AppointmentController::class, 'finish']);

    // Fila de Espera
    Route::apiResource('waitlists', \App\Http\Controllers\WaitlistController::class);
    Route::post('/waitlists/check-matches', [\App\Http\Controllers\WaitlistController::class, 'checkMatches']);

    Route::apiResource('patients', \App\Http\Controllers\PatientController::class);
    
    // Importador / Exportador
    Route::post('/patients-import', [\App\Http\Controllers\Api\ImportExportController::class, 'import']);
    Route::get('/patients-export', [\App\Http\Controllers\Api\ImportExportController::class, 'export']);
    Route::get('/patients-import-template', [\App\Http\Controllers\Api\ImportExportController::class, 'template']);
    
    // Prontuário Eletrônico (EMR)
    Route::get('/patients/{id}/evolutions', [\App\Http\Controllers\ClinicalEvolutionController::class, 'index']);
    Route::post('/patients/{id}/evolutions', [\App\Http\Controllers\ClinicalEvolutionController::class, 'store']);
    Route::delete('/evolutions/{id}', [\App\Http\Controllers\ClinicalEvolutionController::class, 'destroy']);

    Route::get('/patients/{id}/prescriptions', [\App\Http\Controllers\PrescriptionController::class, 'index']);
    Route::post('/patients/{id}/prescriptions', [\App\Http\Controllers\PrescriptionController::class, 'store']);
    Route::delete('/prescriptions/{id}', [\App\Http\Controllers\PrescriptionController::class, 'destroy']);

    Route::get('/patients/{id}/files', [\App\Http\Controllers\PatientFileController::class, 'index']);
    Route::post('/patients/{id}/files', [\App\Http\Controllers\PatientFileController::class, 'store']);
    Route::delete('/files/{id}', [\App\Http\Controllers\PatientFileController::class, 'destroy']);

    Route::apiResource('team', \App\Http\Controllers\TeamController::class)->middleware('can_manage_team');
    Route::apiResource('appointment-types', AppointmentTypeController::class);

    // Roles & Permissions
    Route::get('/permissions', [RoleController::class, 'permissions']);
    Route::apiResource('roles', RoleController::class);
    
    // Configurações (Settings)
    Route::get('/settings', [App\Http\Controllers\SettingController::class, 'index']);
    Route::put('/settings', [App\Http\Controllers\SettingController::class, 'update']);
    Route::get('/settings/holidays', [App\Http\Controllers\SettingController::class, 'holidays']);

    // WhatsApp Logs
    Route::get('/whatsapp-logs', [App\Http\Controllers\WhatsAppLogController::class, 'index']);

    // Lixeira (Trash)
    Route::get('/trash/status', [App\Http\Controllers\TrashController::class, 'status']);
    Route::get('/trash', [\App\Http\Controllers\TrashController::class, 'index']);
    Route::post('/trash/{type}/{id}/restore', [\App\Http\Controllers\TrashController::class, 'restore']);
    Route::delete('/trash/{type}/{id}/force', [\App\Http\Controllers\TrashController::class, 'forceDelete']);
    
    Route::post('/profile/avatar', [\App\Http\Controllers\ProfileController::class, 'updateAvatar']);
    Route::put('/profile', [\App\Http\Controllers\ProfileController::class, 'updateProfile']);
    Route::post('/profile/room', [\App\Http\Controllers\ProfileController::class, 'setCurrentRoom']);
});
