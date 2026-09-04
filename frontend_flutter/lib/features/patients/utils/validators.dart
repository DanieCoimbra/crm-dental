class AppValidators {
  static bool isValidCpf(String? cpf) {
    if (cpf == null || cpf.isEmpty) return false;
    final numbers = cpf.replaceAll(RegExp(r'[^\d]'), '');
    if (numbers.length != 11) return false;

    // Reject known repeated invalid CPFs
    if (RegExp(r'^(\d)\1{10}$').hasMatch(numbers)) return false;

    // Calculate 1st check digit
    int sum = 0;
    for (int i = 0; i < 9; i++) {
      sum += int.parse(numbers[i]) * (10 - i);
    }
    int digit1 = (sum * 10) % 11;
    if (digit1 == 10) digit1 = 0;
    if (digit1 != int.parse(numbers[9])) return false;

    // Calculate 2nd check digit
    sum = 0;
    for (int i = 0; i < 10; i++) {
      sum += int.parse(numbers[i]) * (11 - i);
    }
    int digit2 = (sum * 10) % 11;
    if (digit2 == 10) digit2 = 0;
    if (digit2 != int.parse(numbers[10])) return false;

    return true;
  }

  static bool isValidPhone(String? phone) {
    if (phone == null || phone.isEmpty) return false;
    final numbers = phone.replaceAll(RegExp(r'[^\d]'), '');
    return numbers.length >= 10 && numbers.length <= 11;
  }

  static String? validateCpf(String? value) {
    if (value == null || value.trim().isEmpty) {
      return 'CPF é obrigatório';
    }
    if (!isValidCpf(value)) {
      return 'CPF inválido';
    }
    return null;
  }

  static String? validatePhone(String? value) {
    if (value == null || value.trim().isEmpty) {
      return 'Telefone é obrigatório';
    }
    if (!isValidPhone(value)) {
      return 'Informe um telefone válido com DDD (10 ou 11 dígitos)';
    }
    return null;
  }

  static String? validateEmail(String? value) {
    if (value == null || value.trim().isEmpty) return null; // Email pode ser opcional
    final emailRegex = RegExp(r'^[\w-\.]+@([\w-]+\.)+[\w-]{2,4}$');
    if (!emailRegex.hasMatch(value.trim())) {
      return 'E-mail inválido';
    }
    return null;
  }
}
