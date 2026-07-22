import React from 'react';
import DatePicker, { registerLocale } from 'react-datepicker';
import "react-datepicker/dist/react-datepicker.css";
import { ptBR } from 'date-fns/locale/pt-BR';

// Registrar o locale pt-BR para que o calendário fique em português
registerLocale('pt-BR', ptBR);

export default function CustomDatePicker({ 
  selected, 
  onChange, 
  showTimeSelect = false,
  placeholderText = "Selecione a data...",
  ...props 
}) {
  return (
    <DatePicker
      selected={selected}
      onChange={onChange}
      locale="pt-BR"
      dateFormat={showTimeSelect ? "dd/MM/yyyy HH:mm" : "dd/MM/yyyy"}
      showTimeSelect={showTimeSelect}
      timeFormat="HH:mm"
      timeIntervals={15}
      timeCaption="Hora"
      placeholderText={placeholderText}
      className="custom-datepicker-input"
      calendarClassName="custom-datepicker-calendar"
      popperClassName="custom-datepicker-popper"
      showPopperArrow={false}
      showMonthDropdown
      showYearDropdown
      dropdownMode="select"
      fixedHeight
      portalId="root-portal"
      {...props}
    />
  );
}
