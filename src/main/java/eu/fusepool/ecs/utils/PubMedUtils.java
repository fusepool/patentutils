package eu.fusepool.ecs.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.AccessController;
import java.security.AllPermission;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import org.apache.clerezza.platform.graphprovider.content.ContentGraphProvider;
import org.apache.clerezza.rdf.core.Literal;
import org.apache.clerezza.rdf.core.LiteralFactory;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.TripleCollection;
import org.apache.clerezza.rdf.core.TypedLiteral;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.access.LockableMGraph;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.ontologies.DC;
import org.apache.clerezza.rdf.ontologies.DCTERMS;
import org.apache.clerezza.rdf.ontologies.RDF;
import org.apache.clerezza.rdf.ontologies.SIOC;
import org.apache.clerezza.rdf.utils.GraphNode;
import org.apache.clerezza.utils.Uri;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.stanbol.enhancer.servicesapi.ChainManager;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.ContentItemFactory;
import org.apache.stanbol.enhancer.servicesapi.ContentSource;
import org.apache.stanbol.enhancer.servicesapi.EnhancementException;
import org.apache.stanbol.enhancer.servicesapi.EnhancementJobManager;
import org.apache.stanbol.enhancer.servicesapi.impl.ByteArraySource;
import org.apache.stanbol.enhancer.servicesapi.rdf.Properties;
import org.apache.stanbol.enhancer.servicesapi.rdf.TechnicalClasses;
import org.apache.stanbol.entityhub.model.clerezza.RdfValueFactory;
import org.apache.stanbol.entityhub.servicesapi.model.Entity;
import org.apache.stanbol.entityhub.servicesapi.model.Representation;
import org.apache.stanbol.entityhub.servicesapi.site.SiteManager;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fusepool.ecs.ontologies.ECS;


/**
 * Add dcterm:subject and sioc:content properties to pmo:PatentPublication. The
 * content is created from patent's title and abstract.
 */
@Component
@Service(Object.class)
@Property(name = "javax.ws.rs", boolValue = true)
@Path("pubmed")
public class PubMedUtils {
	
	/**
     * Using slf4j for normal logging
     */
    private static final Logger log = LoggerFactory.getLogger(PatentUtils.class);
    
    //Confidence threshold to accept entities found by an NLP enhancement process
    private static final double CONFIDENCE_THRESHOLD = 0.3;
    
    /**
     * This service allows to get entities from configures sites
     */
    @Reference
    private SiteManager siteManager;
    /**
     * This service allows accessing and creating persistent triple collections
     */
    @Reference
    private ContentGraphProvider contentGraphProvider;
    @Reference
    private Parser parser;
    @Reference
    private ContentItemFactory contentItemFactory;
    @Reference
    private EnhancementJobManager enhancementJobManager;
    @Reference
    private ChainManager chainManager;

    /**
     * This is the name of the graph in which we "log" the requests
     */
    @Activate
    protected void activate(ComponentContext context) {
        log.info("The PubMedUtils service is being activated");

    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        log.info("The PubMedUtils service is being deactivated");
    }
    
    /**
     * Test service component
     *
     */
    @GET
    @Path("test")
    @Produces("text/plain")
    public String testService(@Context final UriInfo uriInfo,
            @QueryParam("query") final String query) throws Exception {
        AccessController.checkPermission(new AllPermission());
        String uriInfoStr = uriInfo.getRequestUri().toString();
        
        String response = "no query. Add a query param for a response message /test?query=<your query>";
        
        if(!(query == null)) {
        	response = "parrot's response: " + query;
        }
        
        return "Test PubMedUtils service Ok. Request URI: " + uriInfoStr + ". Response: " + response;
    }
    
    /**
     * Filter all bibo:Document that do not have a sioc:content property then for each of these resources does the following
     * 1) create a text content from the dcterms:title and dcterms:abstract properties
     * 2) add a new sioc:content property to the resource with the created text content
     * 3) create a content item with the same resource's uri using the text content and send this content item to a default chain for enhancements
     * 4) for each enhancement found adds a dc:subject property to the resource (i.e.: <:documemt> <cd:subject> <:enhancement>)
     * 5) adds other dc:subject statements for pmo:inventor(s) and pmo:applicant(s) 
     *  
     */
    @GET
    @Path("enrich")
    @Produces("text/plain")
    public String enrichPubMed() throws IOException, EnhancementException {
    	// select all the resources that are pmo:PatentPublication and do not have a sioc:content property 
        Set<GraphNode> unenrichedPatents = getUnenrichedArticles();
        for (GraphNode graphNode : unenrichedPatents) {
            log.info("enriching: " + graphNode.getNode());
            enrich(graphNode);
        }

        return "Enriched " + unenrichedPatents.size() + " PubMed articles";
    }
    
    /*
     * Select all bibo:Document that do not have a sioc:content property
     */
    private Set<GraphNode> getUnenrichedArticles() {
        
        Set<GraphNode> result = new HashSet<GraphNode>();
        LockableMGraph contentGraph = getContentGraph();
        Lock l = contentGraph.getLock().readLock();
        l.lock();
        try {
            
            Iterator<Triple> idocument = contentGraph.filter(null, RDF.type, BibliographicOntology.Document);
            while (idocument.hasNext()) {
                Triple triple = idocument.next();
                GraphNode node = new GraphNode(triple.getSubject(),
                        contentGraph);
                if (!node.getObjects(SIOC.content).hasNext()) {
                    result.add(node);
                }
            }
        } finally {
            l.unlock();
        }
        return result;
    }
    
    /*
     * Add a sioc:content property to a resource. The content is created from dcterm:title and dcterms:abstract properties 
     */

    private void enrich(GraphNode resourceNode) throws IOException,
            EnhancementException {
    
    	String textContent = "";
    	
        Iterator<Literal> titles = resourceNode.getLiterals(DCTERMS.title);
        while (titles.hasNext()) {
            textContent += titles.next().getLexicalForm() + " ";
        }
        
        Iterator<Literal> abstracts = resourceNode.getLiterals(DCTERMS.abstract_);
        while (abstracts.hasNext()) {
            textContent += abstracts.next().getLexicalForm() + " ";
        }

        //send the text (title + abstract) to the default chain for enhancements
        enhance(textContent, resourceNode);

        resourceNode.addPropertyValue(SIOC.content, textContent);

        //add a dc:subject statement to each contributor
        aliasAsDcSubject(resourceNode, DCTERMS.contributor);        

        // Resources with this type have sioc:content and rdfs:label indexed by the ECS when added to the content graph
        resourceNode.addProperty(RDF.type, ECS.ContentItem);

    }
    
    /**
     * Add dc:subject properties to
     * <code>node</code> pointing to entities which are assumed to be related to
     * <code>content</code>. This method uses the enhancementJobManager to get
     * related entities using the default chain. The node uri is also the uri of the content item
     * so that the enhancements will be referred that node. Each enhancement found above a threshold is then
     * added as a dc:subject of the node
     */
    private void enhance(String content, GraphNode resourceNode) throws IOException,
            EnhancementException {
        final ContentSource contentSource = new ByteArraySource(
                content.getBytes(), "text/plain");
        final ContentItem contentItem = contentItemFactory.createContentItem(
                (UriRef) resourceNode.getNode(), contentSource);
        enhancementJobManager.enhanceContent(contentItem);
        // this contains the enhancement results
        final MGraph enhancementGraph = contentItem.getMetadata();
        addSubjects(resourceNode, enhancementGraph);
    }
    
    /*
     * Add a dc:subject statement to a resource (document) for each entity that is linked to that resource through the predicate argument.  
     */
    private void aliasAsDcSubject(GraphNode resourceNode, UriRef predicate) {
        Set<Resource> objectSet = new HashSet<Resource>();
        Lock lock = resourceNode.readLock();
        lock.lock();
        try {
            final Iterator<Resource> relatedResources = resourceNode
                    .getObjects(predicate);
            while (relatedResources.hasNext()) {
                Resource resource = relatedResources.next();
                objectSet.add(resource);
            }
        } finally {
            lock.unlock();
        }
        for (Resource predicateObject : objectSet) {
            resourceNode.addProperty(DC.subject, predicateObject);
        }
    }
    
    /* 
     * Add dc:subject property to a pmo:PatentPublication with entities extracted by enhancement engines in the default chain
     * 
     */
    /**
     * Given a node and a TripleCollection containing fise:Enhancements about
     * this node this method adds dc:subject properties to it pointing to
     * entities referenced by those enhancements if the enhancement confidence
     * value is above a threshold.
     *
     * @param node
     * @param metadata
     */
    private void addSubjects(GraphNode resourceNode, TripleCollection metadata) {
        final GraphNode enhancementType = new GraphNode(
                TechnicalClasses.ENHANCER_ENHANCEMENT, metadata);
        final Set<UriRef> entities = new HashSet<UriRef>();
        final Iterator<GraphNode> enhancements = enhancementType
                .getSubjectNodes(RDF.type);
        while (enhancements.hasNext()) {
            final GraphNode enhhancement = enhancements.next();
            // Add dc:subject to the resource for each referenced entity
            final Iterator<Resource> referencedEntities = enhhancement.getObjects(Properties.ENHANCER_ENTITY_REFERENCE);
            while (referencedEntities.hasNext()) {
                final UriRef entity = (UriRef) referencedEntities.next();
                GraphNode entityNode = new GraphNode(entity, metadata);
                Iterator<Literal> confidenceLevels = entityNode.getLiterals(TechnicalClasses.FNHANCER_CONFIDENCE_LEVEL);
                if (!confidenceLevels.hasNext()) {
                    continue;
                }
                double confidenceLevel = LiteralFactory.getInstance().createObject(Double.class, (TypedLiteral) confidenceLevels.next());
                if (confidenceLevel >= CONFIDENCE_THRESHOLD) {
                	resourceNode.addProperty(DC.subject, entity);
                    entities.add(entity);
                }
            }


        }
        for (UriRef uriRef : entities) {
            // We don't get the entity description directly from metadata
            // as the context there would include
            addResourceDescription(uriRef, (MGraph) resourceNode.getGraph());
        }
    }

    /* 
     * Add a description of the entities found in the text by engines in the default chain
     */
    private void addResourceDescription(UriRef iri, MGraph mGraph) {
        final Entity entity = siteManager.getEntity(iri.getUnicodeString());
        if (entity != null) {
            final RdfValueFactory valueFactory = new RdfValueFactory(mGraph);
            final Representation representation = entity.getRepresentation();
            if (representation != null) {
                valueFactory.toRdfRepresentation(representation);
            }
        }
    }
    
    /**
     * This returns the existing MGraph for the log .
     *
     * @return the MGraph to which the requests are logged
     */
    private LockableMGraph getContentGraph() {
        return contentGraphProvider.getContentGraph();
    }


}
