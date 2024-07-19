package com.mmp.beacon.company.domain;

import com.mmp.beacon.global.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "company")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_no")
    private Long id;

    @Column(name = "company_name", length = 50, nullable = false)
    private String name;

    // Setter method for companyName
    public void setCompanyName(String name) {
        this.name = name;
    }
}
