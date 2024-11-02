package com.web.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreateVaccineRequest {

    private String name;

    private String description;

    private String image;

    private Integer price;

    private Long vaccineTypeId;

    private Long manufacturerId;

    private Long ageGroupId;

    private String status;
}
