package net.toadhead.ha.screensaver.config;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * YAML 1.1 reads an unquoted time such as {@code 18:00} as the base-60 number
 * 1080, so a whole number of minutes after midnight binds as that time of day.
 */
@Component
@ConfigurationPropertiesBinding
class YamlTimeOfDayConverter implements Converter<Integer, LocalTime> {

	@Override
	public LocalTime convert(Integer minutes) {
		return LocalTime.of(minutes / 60, minutes % 60);
	}

}
