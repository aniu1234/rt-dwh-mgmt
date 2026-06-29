package com.rtdwh.repository;

import com.rtdwh.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    Optional<SysPermission> findByPermCode(String permCode);

    boolean existsByPermCode(String permCode);
}
