<?php

namespace App\Traits;

use App\Scopes\TenantScope;
use Illuminate\Support\Facades\Auth;

trait Tenantable
{
    protected static function bootTenantable()
    {
        static::addGlobalScope(new TenantScope);

        static::creating(function ($model) {
            if (Auth::hasUser() && Auth::user()->clinic_id) {
                $model->clinic_id = Auth::user()->clinic_id;
            }
        });
    }

    public function clinic()
    {
        return $this->belongsTo(\App\Models\Clinic::class);
    }
}
