package com.cgc.service.llm.utils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

/**
 * @author: anascreations
 *
 */
public class CacheBuilder<K, V> {
	private final Caffeine<Object, Object> caffeine;

	private CacheBuilder() {
		this.caffeine = Caffeine.newBuilder();
	}

	public static <K, V> CacheBuilder<K, V> newBuilder() {
		return new CacheBuilder<>();
	}

	public CacheBuilder<K, V> maximumSize(long maximumSize) {
		caffeine.maximumSize(maximumSize);
		return this;
	}

	public CacheBuilder<K, V> expireAfterAccess(long duration, TimeUnit unit) {
		caffeine.expireAfterAccess(duration, unit);
		return this;
	}

	public CacheBuilder<K, V> expireAfterWrite(long duration, TimeUnit unit) {
		caffeine.expireAfterWrite(duration, unit);
		return this;
	}

	public LoadingCache<K, V> build(CacheLoader<K, V> loader) {
		return caffeine.build(loader);
	}

	public <T> LoadingCache<K, V> build(Function<K, V> mappingFunction) {
		return caffeine.build(mappingFunction::apply);
	}
}
