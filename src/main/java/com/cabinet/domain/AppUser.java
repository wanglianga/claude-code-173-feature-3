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

    /** 补货员评分（满分 100，差异确认补货员责任时扣分） */
    @Builder.Default
    private Integer score = 100;

    /** 补货员待结算/已确认的责任数量（补货结算口径，差异确认后累加） */
    @Builder.Default
    private Integer liabilityQty = 0;

    @Builder.Default
    private boolean enabled = true;
}
