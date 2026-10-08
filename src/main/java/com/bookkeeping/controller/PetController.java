package com.bookkeeping.controller;

import com.bookkeeping.service.PetService;
import com.bookkeeping.service.PetService.PetView;
import lombok.AllArgsConstructor;
import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪伴宠物。路径经 WebConfig 加前缀后为 /api/pet。
 */
@RestController
@RequestMapping("pet")
@AllArgsConstructor
public class PetController {
    private final PetService petService;

    @GetMapping
    public DataResponse<PetView> current() {
        return DataResponse.of(petService.current());
    }

    @PostMapping
    public DataResponse<PetView> adopt(@RequestBody PetRequest request) {
        return DataResponse.of(petService.adopt(request.name(), request.species()));
    }

    @PutMapping
    public DataResponse<PetView> rename(@RequestBody PetRenameRequest request) {
        return DataResponse.of(petService.rename(request.name()));
    }

    public record PetRequest(String name, String species) {
    }

    public record PetRenameRequest(String name) {
    }
}
