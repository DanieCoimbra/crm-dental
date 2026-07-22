<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class CheckManagerRole
{
    public function handle(Request $request, Closure $next): Response
    {
        if ($request->user() && ($request->user()->hasRole('manager') || $request->user()->hasPermissionTo('manage_team'))) {
            return $next($request);
        }

        return response()->json(['message' => 'Unauthorized. Only managers can perform this action.'], 403);
    }
}
