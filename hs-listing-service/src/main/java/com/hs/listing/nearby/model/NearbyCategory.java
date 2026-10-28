package com.hs.listing.nearby.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Getter
@Setter
@Table(name = "nearby_categories")
public class NearbyCategory {
    @Id
    @Column(nullable = false, length = 30)
    private String code;
    @Column(name = "display_name_vi", nullable = false, length = 100)
    private String displayNameVi;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean enabled = true;
}
