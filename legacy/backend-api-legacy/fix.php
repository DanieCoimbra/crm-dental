<?php

setPermissionsTeamId(2);
$user = \App\Models\User::find(5);
if ($user) {
    $user->assignRole('owner');
    echo "Role assigned to user 5.\n";
} else {
    echo "User 5 not found.\n";
}
