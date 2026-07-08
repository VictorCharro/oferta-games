package com.ofertagames.backend.deals;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deals")
public class DealsController {
  private final DealsRepository deals;

  DealsController(DealsRepository deals) {
    this.deals = deals;
  }

  @GetMapping("/top")
  List<DealDto> top(
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "discount") String sort
  ) {
    int safeSize = Math.min(200, Math.max(1, size));
    return deals.findTopDeals(safeSize, sort);
  }
}
