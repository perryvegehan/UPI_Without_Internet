package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);

    @Query(value = """
            select t from Transaction t
            where (:status is null or t.status = :status)
              and (:senderVpa is null or lower(t.senderVpa) like lower(concat('%', :senderVpa, '%')))
              and (:receiverVpa is null or lower(t.receiverVpa) like lower(concat('%', :receiverVpa, '%')))
            order by t.id desc
            """,
            countQuery = """
            select count(t) from Transaction t
            where (:status is null or t.status = :status)
              and (:senderVpa is null or lower(t.senderVpa) like lower(concat('%', :senderVpa, '%')))
              and (:receiverVpa is null or lower(t.receiverVpa) like lower(concat('%', :receiverVpa, '%')))
            """)
    Page<Transaction> search(
            @Param("status") Transaction.Status status,
            @Param("senderVpa") String senderVpa,
            @Param("receiverVpa") String receiverVpa,
            Pageable pageable);
}
