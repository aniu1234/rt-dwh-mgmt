package com.rtdwh.repository;
import com.rtdwh.entity.DataServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DataServiceDefinitionRepository extends JpaRepository<DataServiceDefinition, Long> {
    Optional<DataServiceDefinition> findByServiceCode(String code);
    boolean existsByServiceCode(String code);
}
