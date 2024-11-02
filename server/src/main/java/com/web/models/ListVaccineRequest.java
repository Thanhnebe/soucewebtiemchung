package com.web.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ListVaccineRequest {
    private String name;
    private Integer price;
    private String status;
    private int page;
    private int limit;
}
