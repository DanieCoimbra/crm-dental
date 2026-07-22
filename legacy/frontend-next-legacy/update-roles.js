const fs = require('fs');

const replacements = [
  {
    file: 'src/app/page.js',
    search: "user.role === 'manager' || user.role === 'receptionist'",
    replace: "['owner', 'manager', 'receptionist'].includes(user?.role)"
  },
  {
    file: 'src/app/patients/page.js',
    search: "user.role === 'manager' || user.role === 'receptionist'",
    replace: "['owner', 'manager', 'receptionist'].includes(user?.role)"
  },
  {
    file: 'src/app/settings/page.js',
    search: "const isManager = user && user.role === 'manager'",
    replace: "const isManager = user && ['owner', 'manager'].includes(user.role)"
  },
  {
    file: 'src/app/settings/page.js',
    search: "editingRole && editingRole.name === 'manager'",
    replace: "editingRole && ['owner', 'manager'].includes(editingRole.name)"
  },
  {
    file: 'src/app/settings/page.js',
    search: "manager: 'Gerente'",
    replace: "owner: 'Dono', manager: 'Gerente'"
  },
  {
    file: 'src/app/team/page.js',
    search: "const isManager = user && user.role === 'manager'",
    replace: "const isManager = user && ['owner', 'manager'].includes(user.role)"
  },
  {
    file: 'src/app/team/page.js',
    search: "user.role === 'manager' && user.id !== selectedMember.id",
    replace: "['owner', 'manager'].includes(user.role) && user.id !== selectedMember.id"
  },
  {
    file: 'src/app/team/page.js',
    search: "manager: 'Gerente'",
    replace: "owner: 'Dono', manager: 'Gerente'"
  },
  {
    file: 'src/app/team/page.js',
    search: "member.role === 'doctor' ? 'rgba(16, 185, 129, 0.1)' : member.role === 'manager' ? 'rgba(239, 68, 68, 0.1)' : 'rgba(59, 130, 246, 0.1)'",
    replace: "member.role === 'owner' ? 'rgba(139, 92, 246, 0.1)' : member.role === 'manager' ? 'rgba(239, 68, 68, 0.1)' : member.role === 'doctor' ? 'rgba(16, 185, 129, 0.1)' : 'rgba(59, 130, 246, 0.1)'"
  },
  {
    file: 'src/app/team/page.js',
    search: "member.role === 'doctor' ? '#10b981' : member.role === 'manager' ? '#ef4444' : '#3b82f6'",
    replace: "member.role === 'owner' ? '#8b5cf6' : member.role === 'manager' ? '#ef4444' : member.role === 'doctor' ? '#10b981' : '#3b82f6'"
  },
  {
    file: 'src/app/trash/page.js',
    search: "user.role === 'manager' || user.role === 'receptionist'",
    replace: "['owner', 'manager', 'receptionist'].includes(user.role)"
  },
  {
    file: 'src/app/trash/page.js',
    search: "user.role === 'manager'",
    replace: "['owner', 'manager'].includes(user?.role)"
  },
  {
    file: 'src/app/whatsapp-logs/page.js',
    search: "user.role === 'manager'",
    replace: "['owner', 'manager'].includes(user.role)"
  },
  {
    file: 'src/components/DashboardLayout.js',
    search: "user.role === 'manager' || user.role === 'receptionist'",
    replace: "['owner', 'manager', 'receptionist'].includes(user.role)"
  },
  {
    file: 'src/components/DashboardLayout.js',
    search: "const isManager = user.role === 'manager'",
    replace: "const isManager = ['owner', 'manager'].includes(user?.role)"
  },
  {
    file: 'src/components/PatientEMR.js',
    search: "user.role === 'manager'",
    replace: "['owner', 'manager'].includes(user.role)"
  }
];

replacements.forEach(rep => {
  try {
    let content = fs.readFileSync(rep.file, 'utf8');
    content = content.replaceAll(rep.search, rep.replace);
    fs.writeFileSync(rep.file, content);
    console.log(`Updated ${rep.file}`);
  } catch (err) {
    console.error(`Error updating ${rep.file}: ${err.message}`);
  }
});
