<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use App\Http\Requests\Auth\LoginRequest;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\Auth;

class AuthenticatedSessionController extends Controller
{
    /**
     * Handle an incoming authentication request.
     */
    public function store(LoginRequest $request): Response
    {
        $request->authenticate();

        $user = Auth::user();
        if ($user->clinic_id) {
            $clinic = \App\Models\Clinic::find($user->clinic_id);
            if ($clinic) {
                if ($clinic->status === 'inactive') {
                    Auth::guard('web')->logout();
                    $request->session()->invalidate();
                    $request->session()->regenerateToken();
                    abort(403, 'Sua clínica está inativa.');
                }
                if ($clinic->status === 'trial' && $clinic->trial_ends_at && $clinic->trial_ends_at->isPast()) {
                    Auth::guard('web')->logout();
                    $request->session()->invalidate();
                    $request->session()->regenerateToken();
                    abort(403, 'Trial Expirado');
                }
            }
        }

        $request->session()->regenerate();

        return response()->noContent();
    }

    /**
     * Destroy an authenticated session.
     */
    public function destroy(Request $request): Response
    {
        Auth::guard('web')->logout();

        $request->session()->invalidate();

        $request->session()->regenerateToken();

        return response()->noContent();
    }
}
