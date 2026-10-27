package crm_data.document.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentType;

public interface ClinicalDocumentRepository extends JpaRepository<ClinicalDocument, UUID> {

	/**
	 * Como {@code findById}, mas por JPQL: o filtro Hibernate do tenant se aplica ({@code find} por id não o aplica) e a RLS
	 * segue como barreira final. Id de outra clínica não volta.
	 */
	@Query("select d from ClinicalDocument d where d.id = :id")
	Optional<ClinicalDocument> findOwnById(@Param("id") UUID id);

	String SUMMARY = "select new crm_data.document.repository.DocumentSummaryRow(d.id, d.patientId, d.appointmentId, d.type, "
			+ "d.number, d.issuedOn, d.restDays, d.targetSpecialty, d.voidedAt, d.voidedBy, d.anonymizedAt, d.createdAt, d.createdBy) "
			+ "from ClinicalDocument d where d.patientId = :patientId";

	/** Lista do paciente, sem {@code content}; a ordem vem do {@link Pageable} ({@code createdAt DESC, id DESC}). */
	@Query(value = SUMMARY, countQuery = "select count(d) from ClinicalDocument d where d.patientId = :patientId")
	Page<DocumentSummaryRow> summaries(@Param("patientId") UUID patientId, Pageable pageable);

	@Query(value = SUMMARY + " and d.type = :type",
			countQuery = "select count(d) from ClinicalDocument d where d.patientId = :patientId and d.type = :type")
	Page<DocumentSummaryRow> summariesOfType(@Param("patientId") UUID patientId, @Param("type") DocumentType type,
			Pageable pageable);
}
