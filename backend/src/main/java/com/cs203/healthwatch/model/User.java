package com.cs203.healthwatch.model;

import com.cs203.healthwatch.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @Column(unique = true, nullable = false)
    private String username;

    /** Null once the account is anonymised (CG-7). */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    /** CG-6. See {@code Region}; null until the user sets a profile. */
    @Column
    private String region;

    /** CG-6. See {@code Audience}. */
    @Column
    private String audience;

    /** CG-6. Codes from {@code HealthCondition}; never free text. */
    @ElementCollection
    @CollectionTable(name = "user_health_conditions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "condition_code", nullable = false)
    private Set<String> healthConditions = new HashSet<>();

    /** CG-7. Set when the account is anonymised; the row and id are kept so audit_log stays valid. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public User(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
