/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.config.annotation.method.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.intercept.method.MockMethodInvocation;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;
import org.springframework.security.authentication.TestAuthentication;
import org.springframework.security.authorization.method.AuthorizationAdvisorProxyFactory;
import org.springframework.security.authorization.method.AuthorizationAdvisorProxyFactory.TargetVisitor;
import org.springframework.security.authorization.method.AuthorizeReturnObject;
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler;
import org.springframework.security.authorization.method.MethodAuthorizationDeniedPostProcessor;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.test.SpringTestContext;
import org.springframework.security.config.test.SpringTestContextExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * @author Tadaya Tsuyukubo
 */
@ExtendWith(SpringTestContextExtension.class)
public class ReactiveMethodSecurityConfigurationTests {

	public final SpringTestContext spring = new SpringTestContext(this);

	@Autowired(required = false)
	DefaultMethodSecurityExpressionHandler methodSecurityExpressionHandler;

	@Test
	public void rolePrefixWithGrantedAuthorityDefaults() throws NoSuchMethodException {
		this.spring.register(WithRolePrefixConfiguration.class).autowire();
		Authentication authentication = TestAuthentication.authenticatedUser(authorities("CUSTOM_ABC"));
		MockMethodInvocation methodInvocation = new MockMethodInvocation(new Foo(), Foo.class, "bar", String.class);
		EvaluationContext context = this.methodSecurityExpressionHandler.createEvaluationContext(authentication,
				methodInvocation);
		SecurityExpressionRoot root = (SecurityExpressionRoot) context.getRootObject().getValue();
		assertThat(root.hasRole("ROLE_ABC")).isFalse();
		assertThat(root.hasRole("ROLE_CUSTOM_ABC")).isFalse();
		assertThat(root.hasRole("CUSTOM_ABC")).isTrue();
		assertThat(root.hasRole("ABC")).isTrue();
	}

	@Test
	public void rolePrefixWithDefaultConfig() throws NoSuchMethodException {
		this.spring.register(ReactiveMethodSecurityConfiguration.class).autowire();
		Authentication authentication = TestAuthentication.authenticatedUser(authorities("ROLE_ABC"));
		MockMethodInvocation methodInvocation = new MockMethodInvocation(new Foo(), Foo.class, "bar", String.class);
		EvaluationContext context = this.methodSecurityExpressionHandler.createEvaluationContext(authentication,
				methodInvocation);
		SecurityExpressionRoot root = (SecurityExpressionRoot) context.getRootObject().getValue();
		assertThat(root.hasRole("ROLE_ABC")).isTrue();
		assertThat(root.hasRole("ABC")).isTrue();
	}

	@Test
	public void rolePrefixWithGrantedAuthorityDefaultsAndSubclassWithProxyingEnabled() throws NoSuchMethodException {
		this.spring.register(SubclassConfig.class).autowire();
		Authentication authentication = TestAuthentication.authenticatedUser(authorities("ROLE_ABC"));
		MockMethodInvocation methodInvocation = new MockMethodInvocation(new Foo(), Foo.class, "bar", String.class);
		EvaluationContext context = this.methodSecurityExpressionHandler.createEvaluationContext(authentication,
				methodInvocation);
		SecurityExpressionRoot root = (SecurityExpressionRoot) context.getRootObject().getValue();
		assertThat(root.hasRole("ROLE_ABC")).isTrue();
		assertThat(root.hasRole("ABC")).isTrue();
	}

	@Test
	public void findByIdWhenAuthorizedResultThenAuthorizes() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("airplane:read"));
		StepVerifier
			.create(flights.findById("1")
				.flatMap(Flight::getAltitude)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier
			.create(flights.findById("1")
				.flatMap(Flight::getSeats)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNextCount(1)
			.verifyComplete();
	}

	@Test
	public void findByIdWhenUnauthorizedResultThenDenies() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("seating:read"));
		StepVerifier
			.create(flights.findById("1")
				.flatMap(Flight::getSeats)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier
			.create(flights.findById("1")
				.flatMap(Flight::getAltitude)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.verifyError(AccessDeniedException.class);
	}

	@Test
	public void findAllWhenUnauthorizedResultThenDenies() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("seating:read"));
		StepVerifier
			.create(flights.findAll()
				.flatMap(Flight::getSeats)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNextCount(2)
			.verifyComplete();
		StepVerifier
			.create(flights.findAll()
				.flatMap(Flight::getAltitude)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.verifyError(AccessDeniedException.class);
	}

	@Test
	public void removeWhenAuthorizedResultThenRemoves() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("seating:read"));
		StepVerifier.create(flights.remove("1").contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.verifyComplete();
	}

	@Test
	public void findAllWhenPostFilterThenFilters() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("airplane:read"));
		StepVerifier
			.create(flights.findAll()
				.flatMap(Flight::getPassengers)
				.flatMap(Passenger::getName)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNext("Marie Curie", "Ada Lovelace", "Albert Einstein")
			.verifyComplete();
	}

	@Test
	public void findAllWhenPreFilterThenFilters() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("airplane:read"));
		StepVerifier
			.create(flights.findAll()
				.flatMap((flight) -> flight.board(Flux.just("John Doe", "John")).then(Mono.just(flight)))
				.flatMap(Flight::getPassengers)
				.flatMap(Passenger::getName)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.expectNext("Marie Curie", "Ada Lovelace", "John Doe", "Albert Einstein", "John Doe")
			.verifyComplete();
	}

	@Test
	public void findAllWhenNestedPreAuthorizeThenAuthorizes() {
		this.spring.register(AuthorizeResultConfig.class).autowire();
		FlightRepository flights = this.spring.getContext().getBean(FlightRepository.class);
		Authentication pilot = TestAuthentication.authenticatedUser(authorities("seating:read"));
		StepVerifier
			.create(flights.findAll()
				.flatMap(Flight::getPassengers)
				.flatMap(Passenger::getName)
				.contextWrite(ReactiveSecurityContextHolder.withAuthentication(pilot)))
			.verifyError(AccessDeniedException.class);
	}

	@Test
	@WithMockUser
	void getUserWhenNotAuthorizedThenHandlerUsesCustomAuthorizationDecision() {
		this.spring.register(MethodSecurityServiceConfig.class, CustomResultConfig.class).autowire();
		ReactiveMethodSecurityService service = this.spring.getContext().getBean(ReactiveMethodSecurityService.class);
		MethodAuthorizationDeniedHandler handler = this.spring.getContext()
			.getBean(MethodAuthorizationDeniedHandler.class);
		MethodAuthorizationDeniedPostProcessor postProcessor = this.spring.getContext()
			.getBean(MethodAuthorizationDeniedPostProcessor.class);
		assertThat(service.checkCustomResult(false).block()).isNull();
		verify(handler).handle(any(), any(Authz.AuthzResult.class));
		verifyNoInteractions(postProcessor);
		assertThat(service.checkCustomResult(true).block()).isNull();
		verify(postProcessor).postProcessResult(any(), any(Authz.AuthzResult.class));
		verifyNoMoreInteractions(handler);
	}

	private static Consumer<User.UserBuilder> authorities(String... authorities) {
		return (builder) -> builder.authorities(authorities);
	}

	@Configuration
	@EnableReactiveMethodSecurity // this imports ReactiveMethodSecurityConfiguration
	static class WithRolePrefixConfiguration {

		@Bean
		GrantedAuthorityDefaults grantedAuthorityDefaults() {
			return new GrantedAuthorityDefaults("CUSTOM_");
		}

	}

	@Configuration
	static class SubclassConfig extends ReactiveMethodSecurityConfiguration {

	}

	static class Foo {

		public void bar(String param) {
		}

	}

	@EnableReactiveMethodSecurity
	@Configuration
	static class AuthorizeResultConfig {

		@Bean
		@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
		static Customizer<AuthorizationAdvisorProxyFactory> skipValueTypes() {
			return (factory) -> factory.setTargetVisitor(TargetVisitor.defaultsSkipValueTypes());
		}

		@Bean
		FlightRepository flights() {
			FlightRepository flights = new FlightRepository();
			Flight one = new Flight("1", 35000d, 35);
			one.board(Flux.just("Marie Curie", "Kevin Mitnick", "Ada Lovelace")).block();
			flights.save(one).block();
			Flight two = new Flight("2", 32000d, 72);
			two.board(Flux.just("Albert Einstein")).block();
			flights.save(two).block();
			return flights;
		}

		@Bean
		Function<Passenger, Mono<Boolean>> isNotKevin() {
			return (passenger) -> passenger.getName().map((name) -> !name.equals("Kevin Mitnick"));
		}

	}

	@AuthorizeReturnObject
	static class FlightRepository {

		private final Map<String, Flight> flights = new ConcurrentHashMap<>();

		Flux<Flight> findAll() {
			return Flux.fromIterable(this.flights.values());
		}

		Mono<Flight> findById(String id) {
			return Mono.just(this.flights.get(id));
		}

		Mono<Flight> save(Flight flight) {
			this.flights.put(flight.getId(), flight);
			return Mono.just(flight);
		}

		Mono<Void> remove(String id) {
			this.flights.remove(id);
			return Mono.empty();
		}

	}

	@AuthorizeReturnObject
	static class Flight {

		private final String id;

		private final Double altitude;

		private final Integer seats;

		private final List<Passenger> passengers = new ArrayList<>();

		Flight(String id, Double altitude, Integer seats) {
			this.id = id;
			this.altitude = altitude;
			this.seats = seats;
		}

		String getId() {
			return this.id;
		}

		@PreAuthorize("hasAuthority('airplane:read')")
		Mono<Double> getAltitude() {
			return Mono.just(this.altitude);
		}

		@PreAuthorize("hasAnyAuthority('seating:read', 'airplane:read')")
		Mono<Integer> getSeats() {
			return Mono.just(this.seats);
		}

		@PostAuthorize("hasAnyAuthority('seating:read', 'airplane:read')")
		@PostFilter("@isNotKevin.apply(filterObject)")
		Flux<Passenger> getPassengers() {
			return Flux.fromIterable(this.passengers);
		}

		@PreAuthorize("hasAnyAuthority('seating:read', 'airplane:read')")
		@PreFilter("filterObject.contains(' ')")
		Mono<Void> board(Flux<String> passengers) {
			return passengers.doOnNext((passenger) -> this.passengers.add(new Passenger(passenger))).then();
		}

	}

	public static class Passenger {

		String name;

		public Passenger(String name) {
			this.name = name;
		}

		@PreAuthorize("hasAuthority('airplane:read')")
		public Mono<String> getName() {
			return Mono.just(this.name);
		}

	}

	@EnableReactiveMethodSecurity
	static class CustomResultConfig {

		MethodAuthorizationDeniedHandler handler = mock(MethodAuthorizationDeniedHandler.class);

		MethodAuthorizationDeniedPostProcessor postProcessor = mock(MethodAuthorizationDeniedPostProcessor.class);

		@Bean
		MethodAuthorizationDeniedHandler methodAuthorizationDeniedHandler() {
			return this.handler;
		}

		@Bean
		MethodAuthorizationDeniedPostProcessor methodAuthorizationDeniedPostProcessor() {
			return this.postProcessor;
		}

	}

}
