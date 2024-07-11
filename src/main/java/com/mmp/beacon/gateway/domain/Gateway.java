package com.mmp.beacon.gateway.domain;

import com.mmp.beacon.global.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "gateway")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Gateway extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "gateway_no")
    private Long id;

    @Column(name = "gateway_name", length = 50, nullable = false)
    private String name;
}
