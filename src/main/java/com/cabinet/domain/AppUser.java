package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "app_user",
        uniqueConstraints = @UniqueConstraint(columnNames = "username"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser extends BaseEntity {

    /** 登录名 */
    private String username;

    /** BCrypt 密文 */
    private String password;

    /** REPLENISHER / OPERATOR / FINANCE / CUSTOMER_SERVICE / ADMIN */
    private String role;

    private String displayName;

    /** 补货员评分（初始 100，差异责任确认扣分，改判无责回补） */
    @Builder.Default
    private Integer scorePoints = 100;

    @Builder.Default
    private boolean enabled = true;
}
