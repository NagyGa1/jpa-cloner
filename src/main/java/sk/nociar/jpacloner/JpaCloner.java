package sk.nociar.jpacloner;

import sg.studium.hproxyutil.HProxyUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.persistence.Embeddable;
import javax.persistence.Entity;

import sk.nociar.jpacloner.graphs.GraphExplorer;

/**
 * JpaCloner provides cloning of JPA entity subgraphs. Cloned entities will be instantiated as <b>raw classes</b>.
 * The <b>raw class</b> means a class annotated by {@link Entity} or {@link Embeddable}, not a Hibernate proxy. 
 * String patterns define <b>included relations</b> which will be cloned. For description of patterns see the {@link GraphExplorer}.
 * Cloned entities will have all <b>basic properties</b> (non-relation properties) copied by default.
 * Advanced control over the cloning process is supported via the {@link PropertyFilter} interface.
 * There are two options for cloning:<br/><br/>
 * <ol>
 * <li> 
 * Cloning without a {@link PropertyFilter}. All <b>basic properties</b> of entities are copied by default in this case:
 * <pre>
 * Company cloned = JpaCloner.clone(company, "department+.(boss|employees).address");</pre>
 * </li>
 * <li>
 * Cloning with a {@link PropertyFilter}. The {@link PropertyFilter} implementation serves as an exclusion filter 
 * of <b>relations</b> and <b>basic properties</b>:
 * <pre>
 * PropertyFilter filter = new PropertyFilter() {
 *     public boolean test(Object entity, String property) {
 *         // do not clone primary keys
 *         return !"id".equals(property);
 *     }
 * } 
 * Company cloned = JpaCloner.clone(company, filter, "department+.(boss|employees).address");</pre>
 * </li>
 * </ol>
 * Cloned <b>relations</b> will be standard java.util classes:<br/>
 * {@link Set}-&gt;{@link LinkedHashSet}<br/>
 * {@link Map}-&gt;{@link LinkedHashMap}<br/>
 * {@link List}-&gt;{@link ArrayList}<br/>
 * {@link SortedSet}-&gt;{@link TreeSet}<br/>
 * {@link SortedMap}-&gt;{@link TreeMap}<br/>
 * <br/>
 * Cloning of a {@link Map} is supported via "key" and "value" properties e.g. "my.map.(key.a.b.c|value.x.y.z)".
 * Please note that the cloning has also a side effect regarding the lazy loading. 
 * All entities which will be cloned could be fetched from the DB. It is advisable
 * (but not required) to perform the cloning inside a <b>transaction scope</b>.
 * <br/><br/>
 * Requirements:
 * <ul>
 * <li>JPA entities must <b>correctly</b> implement the {@link Object#equals(Object obj)} 
 * method and the {@link Object#hashCode()} method!</li>
 * </ul>
 * 
 * @author Miroslav Nociar
 */
public final class JpaCloner {
	private JpaCloner() {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * Clones all explored entities and relations.
	 * @param explorer
	 * @return map of original -&gt; clone
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Map<Object, Object> clone(JpaExplorer explorer, PropertyFilter propertyFilter) {
		Map<Object, Object> originalToClone = new HashMap<Object, Object>(explorer.entities.size());
		// clone each explored JPA entity
		for (Object original : explorer.entities.keySet()) {
			JpaClassInfo classInfo = JpaClassInfo.get(HProxyUtil.getClass(original));
			Object clone;
			try {
				clone = classInfo.getConstructor().newInstance();
			} catch (Exception e) {
				throw new IllegalStateException("Unable to clone: " + original, e);
			}
			// copy basic properties
			copyBasicProperties(original, clone, classInfo, propertyFilter);
			// put in the cache
			originalToClone.put(original, clone);
		}
		// clone @ManyToOne, @OneToOne, @Embedded, @EmbeddedId
		for (Map.Entry<Object, Set<String>> entry : explorer.entities.entrySet()) {
			Object original = entry.getKey();
			Set<String> relations = entry.getValue();
			Object clone = originalToClone.get(original);
			JpaClassInfo classInfo = JpaClassInfo.get(original.getClass());
			for (String relation : relations) {
				JpaPropertyInfo propertyInfo = classInfo.getPropertyInfo(relation);
				if (propertyInfo.isSingular()) {
					Object originalValue = propertyInfo.getValue(original);
					Object clonedValue = originalToClone.get(originalValue);
					propertyInfo.setValue(clone, clonedValue);
				}
			}
		}
		// clone @OneToMany, @ManyToMany, @ElementCollection
		for (Map.Entry<Object, Set<String>> entry : explorer.entities.entrySet()) {
			Object original = entry.getKey();
			Set<String> relations = entry.getValue();
			Object clone = originalToClone.get(original);
			JpaClassInfo classInfo = JpaClassInfo.get(original.getClass());
			for (String relation : relations) {
				JpaPropertyInfo propertyInfo = classInfo.getPropertyInfo(relation);
				if (propertyInfo.isSingular()) {
					continue;
				}
				Object originalValue = propertyInfo.getValue(original);
				if (originalValue instanceof Collection) {
					Collection originalCollection = (Collection) originalValue;
					Collection clonedCollection;
					if (originalCollection instanceof SortedSet) {
						// TreeSet with the same Comparator (can be null)
						clonedCollection = new TreeSet(((SortedSet) originalValue).comparator());
					} else if (originalCollection instanceof Set) {
						// HashSet
						clonedCollection = new LinkedHashSet(originalCollection.size());
					} else if (originalCollection instanceof List) {
						// ArrayList
						clonedCollection = new ArrayList(originalCollection.size());
					} else {
						throw new IllegalStateException("Unsupported collection type: " + originalValue.getClass());
					}
					for (Object o : originalCollection) {
						Object c = originalToClone.get(o);
						if (c == null) {
							c = o;
						}
						clonedCollection.add(c);
					}
					propertyInfo.setValue(clone, clonedCollection);
				} else if (originalValue instanceof Map) {
					Map originalMap = (Map) originalValue;
					Map clonedMap;
					if (originalMap instanceof SortedMap) {
						clonedMap = new TreeMap(((SortedMap) originalValue).comparator());
					} else {
						clonedMap = new LinkedHashMap(originalMap.size());
					}
					for (Object o : originalMap.entrySet()) {
						Entry e = (Entry) o;
						Object key = e.getKey();
						Object value = e.getValue();
						Object key2 = originalToClone.get(key);
						Object value2 = originalToClone.get(value);
						if (key2 == null) {
							key2 = key;
						}
						if (value2 == null) {
							value2 = value;
						}
						clonedMap.put(key2, value2);
					}
					propertyInfo.setValue(clone, clonedMap);
				}
			}
		}
		return originalToClone;
	}
	
	/**
	 * Clones the passed JPA entity. The property filter controls the cloning of <b>basic properties</b>.
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T clone(T root, PropertyFilter propertyFilter, String... patterns) {
		JpaExplorer explorer = JpaExplorer.doExplore(root, propertyFilter, patterns);
		return (T) clone(explorer, propertyFilter).get(root);
	}

	/**
	 * Clones the list of JPA entities. The property filter controls the cloning of <b>basic properties</b>.
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> clone(Collection<T> list, PropertyFilter propertyFilter, String... patterns) {
		List<T> clonedList = new ArrayList<T>(list.size());
		JpaExplorer explorer = JpaExplorer.doExplore(list, propertyFilter, patterns);
		Map<Object, Object> originalToClone = clone(explorer, propertyFilter);
		for (T original : list) {
			clonedList.add((T) originalToClone.get(original));
		}
		return clonedList;
	}

	/**
	 * Clones the set of JPA entities. The property filter controls the cloning of <b>basic properties</b>.
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> Set<T> clone(Set<T> set, PropertyFilter propertyFilter, String... patterns) {
		Set<T> clonedSet = new HashSet<T>();
		JpaExplorer explorer = JpaExplorer.doExplore(set, propertyFilter, patterns);
		Map<Object, Object> originalToClone = clone(explorer, propertyFilter);
		for (T original : set) {
			clonedSet.add((T) originalToClone.get(original));
		}
		return clonedSet;
	}

	/**
	 * Clones the passed JPA entity. Each entity has <b>all basic properties</b> cloned. 
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	public static <T> T clone(T root, String... patterns) {
		return clone(root, PropertyFilters.getDefaultFilter(), patterns);
	}

	/**
	 * Clones the list of JPA entities. Each entity has <b>all basic properties</b> cloned. 
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	public static <T> List<T> clone(Collection<T> list, String... patterns) {
		return clone(list, PropertyFilters.getDefaultFilter(), patterns);
	}

	/**
	 * Clones the set of JPA entities. Each entity has <b>all basic properties</b> cloned. 
	 * The cloned relations are specified by string patters. For description of patterns see the {@link GraphExplorer}.
	 */
	public static <T> Set<T> clone(Set<T> set, String... patterns) {
		return clone(set, PropertyFilters.getDefaultFilter(), patterns);
	}

	/**
	 * Copy properties (not relations) from o1 to o2.
	 */
	private static void copyBasicProperties(Object o1, Object o2, JpaClassInfo classInfo, PropertyFilter propertyFilter) {
		for (String property : classInfo.getBaseProperties()) {
			if (propertyFilter.test(o1, property)) {
				JpaPropertyInfo propertyInfo = classInfo.getPropertyInfo(property);
				Object value = propertyInfo.getValue(o1);
				propertyInfo.setValue(o2, value);
			}
		}
	}
	
	/**
	 * Copy all <b>basic properties</b> from the first entity to the second entity.
	 */
	public static <T, X extends T> void copy(T o1, X o2) {
		copy(o1, o2, PropertyFilters.getDefaultFilter());
	}
	
	/**
	 * Copy filtered <b>basic properties</b> from the first entity to the second entity.
	 */
	public static <T, X extends T> void copy(T o1, X o2, PropertyFilter propertyFilter) {
		JpaClassInfo classInfo = JpaClassInfo.get(o1.getClass());
		copyBasicProperties(o1, o2, classInfo, propertyFilter);
	}

}
