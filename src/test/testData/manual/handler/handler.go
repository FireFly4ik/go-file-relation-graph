package handler

import "example.com/relationgraph/service"

type OrderService interface {
	Create() error
}

func Handle(orderService OrderService) error {
	service.Validate()
	return orderService.Create()
}

