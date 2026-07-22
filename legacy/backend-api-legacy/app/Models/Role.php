<?php

namespace App\Models;

use Spatie\Permission\Models\Role as SpatieRole;
use App\Traits\Tenantable;

class Role extends SpatieRole
{
    // Tenantable was causing global roles (clinic_id = null) to be hidden
    // from authenticated users. Spatie manages team permissions via model_has_roles.
}
