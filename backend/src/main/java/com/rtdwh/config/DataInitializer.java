package com.rtdwh.config;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.entity.SysRole;
import com.rtdwh.entity.SysUser;
import com.rtdwh.entity.SysUser.UserStatus;
import com.rtdwh.repository.DatasourceConfigRepository;
import com.rtdwh.repository.SysRoleRepository;
import com.rtdwh.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserRepository userRepo;
    private final SysRoleRepository roleRepo;
    private final DatasourceConfigRepository datasourceRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.init-users.enabled:false}")
    private boolean initUsersEnabled;

    @Value("${app.init-users.admin-password:}")
    private String adminDefaultPassword;

    @Value("${app.init-users.dev-password:}")
    private String devDefaultPassword;

    @Value("${app.init-users.guest-password:}")
    private String guestDefaultPassword;

    @Override
    @Transactional
    public void run(String... args) {
        initRoles();
        if (initUsersEnabled) {
            initUsers();
            initDatasources();
        } else {
            log.info("初始化用户/数据源已禁用（app.init-users.enabled=false），跳过");
        }
    }

    private void initRoles() {
        if (roleRepo.existsByRoleCode("ADMIN")) {
            log.info("角色数据已存在，跳过初始化");
            return;
        }

        log.info("初始化角色数据...");
        roleRepo.save(SysRole.builder().roleCode("ADMIN").roleName("管理员").description("拥有全部权限").build());
        roleRepo.save(SysRole.builder().roleCode("DEVELOPER").roleName("开发者").description("可创建/管理任务和报表").build());
        roleRepo.save(SysRole.builder().roleCode("VISITOR").roleName("访客").description("仅可查看数据和报表").build());
        log.info("角色初始化完成：ADMIN, DEVELOPER, VISITOR");
    }

    private void initUsers() {
        if (userRepo.existsByUsername("admin")) {
            log.info("用户数据已存在，跳过初始化");
            return;
        }

        log.info("初始化用户数据...");

        SysRole adminRole = roleRepo.findByRoleCode("ADMIN").orElseThrow();
        SysRole devRole = roleRepo.findByRoleCode("DEVELOPER").orElseThrow();
        SysRole visitorRole = roleRepo.findByRoleCode("VISITOR").orElseThrow();

        String adminPwd = validateInitPassword(adminDefaultPassword, "admin");
        String devPwd = validateInitPassword(devDefaultPassword, "dev01");
        String guestPwd = validateInitPassword(guestDefaultPassword, "guest");

        // admin
        SysUser admin = SysUser.builder()
                .username("admin")
                .passwordHash(passwordEncoder.encode(adminPwd))
                .realName("管理员")
                .status(UserStatus.active)
                .roles(Set.of(adminRole))
                .build();
        userRepo.save(admin);

        // dev01
        SysUser dev01 = SysUser.builder()
                .username("dev01")
                .passwordHash(passwordEncoder.encode(devPwd))
                .realName("开发者01")
                .status(UserStatus.active)
                .roles(Set.of(devRole))
                .build();
        userRepo.save(dev01);

        // guest
        SysUser guest = SysUser.builder()
                .username("guest")
                .passwordHash(passwordEncoder.encode(guestPwd))
                .realName("访客")
                .status(UserStatus.active)
                .roles(Set.of(visitorRole))
                .build();
        userRepo.save(guest);

        log.info("用户初始化完成（请使用环境变量配置密码）");
    }

    private String validateInitPassword(String pwd, String username) {
        if (pwd == null || pwd.isBlank()) {
            log.warn("用户 {} 的初始化密码未配置（app.init-users.<user>-password），请使用环境变量设置", username);
            log.warn("⚠️  首次登录后请立即修改密码！");
            return pwd; // will fail at encode if empty, but user must configure it
        }
        return pwd;
    }

    private void initDatasources() {
        if (datasourceRepo.count() > 0) {
            log.info("数据源数据已存在，跳过初始化");
            return;
        }

        log.info("初始化数据源数据...");

        // Use placeholder values — real credentials should be configured via frontend
        datasourceRepo.save(DatasourceConfig.builder()
                .creatorId(1L)
                .configName("MySQL-示例库")
                .dbType(DbType.mysql)
                .host("localhost")
                .port(3306)
                .database("example_db")
                .username("root")
                .passwordEncrypted("")
                .extraParams("{\"useSSL\":false,\"serverTimezone\":\"Asia/Shanghai\"}")
                .build());

        log.info("数据源初始化完成（示例数据，请通过前端配置真实数据源）");
    }
}
