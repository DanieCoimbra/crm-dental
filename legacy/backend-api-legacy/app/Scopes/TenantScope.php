<?php

namespace App\Scopes;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Scope;
use Illuminate\Support\Facades\Auth;

class TenantScope implements Scope
{
    public function apply(Builder $builder, Model $model)
    {
        // Se houver um usuário autenticado, filtre pelo clinic_id dele
        if (Auth::hasUser() && Auth::user()->clinic_id) {
            $builder->where($model->getTable() . '.clinic_id', Auth::user()->clinic_id);
        }
    }
}
