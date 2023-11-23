package org.yard.example;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.drools.io.ReaderResource;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.api.marshalling.DMNMarshaller;
import org.kie.dmn.backend.marshalling.v1x.DMNMarshallerFactory;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.dmn.core.internal.utils.DynamicDMNContextBuilder;
import org.kie.dmn.core.internal.utils.MarshallingStubUtils;
import org.kie.dmn.feel.codegen.feel11.CodegenStringUtil;
import org.kie.dmn.model.api.*;
import org.kie.dmn.model.api.DecisionTable;
import org.kie.dmn.model.api.LiteralExpression;
import org.kie.dmn.model.v1_3.*;
import org.yard.model.*;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;

@Path("/packages")
public class YardService {
    private JsonMapper jsonMapper = JsonMapper.builder().build();

    @POST
    @Path("/domestic-package-prices")
    @Produces(MediaType.APPLICATION_JSON)
    public String domesticPackagePrices(String value) {
        return run("domestic-package-prices.yml", value);
    }

    @POST
    @Path("/eu-package-prices")
    @Produces(MediaType.APPLICATION_JSON)
    public String euPackagePrices(String value) {
        return run("eu-package-prices.yml", value);
    }


    @POST
    @Path("/extra-costs")
    @Produces(MediaType.APPLICATION_JSON)
    public String extraCosts(String value) {
        return run("extra-costs.yml", value);
    }


    private String run(final String file, final String value ){
        try {
            Definitions definitions = parse(read(file));

            Map<String, Object> readValue= readJSON(value);

            DMNMarshaller dmnMarshaller = DMNMarshallerFactory.newDefaultMarshaller();
            String xml = dmnMarshaller.marshal(definitions);

            DMNRuntime dmnRuntime = DMNRuntimeBuilder.fromDefaults()
                    .buildConfiguration()
                    .fromResources(Arrays.asList(new ReaderResource(new StringReader(xml))))
                    .getOrElseThrow(RuntimeException::new);
            DMNContext dmnContext = new DynamicDMNContextBuilder(dmnRuntime.newContext(), dmnRuntime.getModels().get(0))
                    .populateContextWith(readValue);
            DMNResult dmnResult = dmnRuntime.evaluateAll(dmnRuntime.getModels().get(0), dmnContext);

            Map<String, Object> onlyOutputs = new LinkedHashMap<>();
            for (DMNDecisionResult r : dmnResult.getDecisionResults()) {
                onlyOutputs.put(r.getDecisionName(), r.getResult());
            }
            // TODO to make a config flag to retain also inputs? that was helpful lesson with dealing with CloudEvents and OB
            Object serialized = MarshallingStubUtils.stubDMNResult(onlyOutputs, Object::toString);
            final String OUTPUT_JSON = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(serialized);


            return OUTPUT_JSON;
        } catch (Exception e ){
            return e.toString();
        }
    }

    private String read(final String name) throws FileNotFoundException {
        final StringBuilder buffer = new StringBuilder();

        final URL resource = getClass().getClassLoader().getResource(name);

        final Scanner sc = new Scanner(new File(resource.getFile()));

        while (sc.hasNextLine()) {
            final String line = sc.nextLine();
            buffer.append(line);
            buffer.append(System.lineSeparator());
        }

        return buffer.toString();
    }
    private Map<String, Object> readJSON(final String CONTEXT) throws Exception {
        return jsonMapper.readValue(CONTEXT, Map.class);
    }
    public Definitions parse(String yaml) throws Exception {

        final YaRD sd = new YaRD_YamlMapperImpl().read(yaml);
//        YaRD sd = mapper.readValue(yaml, YaRD.class);
        Definitions defs = new TDefinitions();
        enrichDefinitions(defs, Optional.ofNullable(sd.getName()).orElse("defaultName"));
        defs.setExpressionLanguage(Optional.ofNullable(sd.getExpressionLang()).orElse(KieDMNModelInstrumentedBase.URI_FEEL));
        appendInputData(defs, sd.getInputs());
        appendDecision(defs, sd.getElements());
        return defs;
    }

    private void appendDecision(Definitions definitions, List<Element> list) {
        for ( Element hi : list) {
            String nameString = (String) hi.getName();
            Decision decision = createDecisionWithWiring(definitions, nameString);
            Expression decisionLogic = createDecisionLogic(hi.getLogic());
            decision.setExpression(decisionLogic);
            definitions.getDrgElement().add(decision);
        }
    }

    private Expression createDecisionLogic(DecisionLogic decisionLogic) {
        if (decisionLogic instanceof org.yard.model.DecisionTable) {
            return createDecisionTable((org.yard.model.DecisionTable) decisionLogic);
        } else if (decisionLogic instanceof org.yard.model.LiteralExpression) {
            return createLiteralExpression((org.yard.model.LiteralExpression) decisionLogic);
        } else {
            throw new UnsupportedOperationException("Not implemented in impl1 / TODO");
        }
    }

    private Expression createLiteralExpression(org.yard.model.LiteralExpression logic) {
        LiteralExpression lt = new TLiteralExpression();
        lt.setText(logic.getExpression());
        return lt;
    }

    private Expression createDecisionTable(org.yard.model.DecisionTable logic) {
        List<String> inputs = logic.getInputs();
        List<Rule> rules = logic.getRules();
        DecisionTable dt = new TDecisionTable();
        dt.setHitPolicy(HitPolicy.fromValue(logic.getHitPolicy()));
        for (String i : inputs) {
            InputClause ic = new TInputClause();
            ic.setLabel(i);
            LiteralExpression le = new TLiteralExpression();
            le.setText(i);
            ic.setInputExpression(le);
            dt.getInput().add(ic);
        }
        // TODO check if DT defines a set of outputsComponents.
        OutputClause oc = new TOutputClause();
        dt.getOutput().add(oc);
        for (Rule yamlRule : rules) {
            DecisionRule dr = createDecisionRule(yamlRule);
            dt.getRule().add(dr);
        }
        return dt;
    }

    private DecisionRule createDecisionRule(Rule yamlRule) {
        if (yamlRule instanceof WhenThenRule) {
            return createDecisionRuleWhenThen((WhenThenRule) yamlRule);
        } else if (yamlRule instanceof InlineRule) {
            throw new UnsupportedOperationException("Rule in simple array form not supported; use WhenThenRule form.");
        } else {
            throw new UnsupportedOperationException("?");
        }
    }

    private DecisionRule createDecisionRuleWhenThen(WhenThenRule yamlRule) {
        List<Object> when = yamlRule.getWhen();
        Object then = yamlRule.getThen();
        DecisionRule dr = new TDecisionRule();
        for (Object w : when) {
            UnaryTests ut = new TUnaryTests();
            ut.setText(w.toString());
            dr.getInputEntry().add(ut);
        }
        if (!(then instanceof Map)) {
            LiteralExpression le = new TLiteralExpression();
            le.setText(then.toString());
            dr.getOutputEntry().add(le);
        } else {
            throw new UnsupportedOperationException("TODO complex output type value not supported yet.");
        }
        return dr;
    }

    private Decision createDecisionWithWiring(Definitions definitions, String nameString) {
        Decision decision = new TDecision();
        decision.setName(nameString);
        decision.setId("d_" + CodegenStringUtil.escapeIdentifier(nameString));
        InformationItem variable = new TInformationItem();
        variable.setName(nameString);
        variable.setId("dvar_" + CodegenStringUtil.escapeIdentifier(nameString));
        variable.setTypeRef(new QName("Any"));
        decision.setVariable(variable);
        // TODO, for the moment, we hard-code the wiring of the requirement in the order of the YAML
        for (DRGElement drgElement : definitions.getDrgElement()) {
            InformationRequirement ir = new TInformationRequirement();
            DMNElementReference er = new TDMNElementReference();
            er.setHref("#" + drgElement.getId());
            if (drgElement instanceof InputData) {
                ir.setRequiredInput(er);
            } else if (drgElement instanceof Decision) {
                ir.setRequiredDecision(er);
            } else {
                throw new IllegalStateException();
            }
            decision.getInformationRequirement().add(ir);
        }
        return decision;
    }

    private void appendInputData(Definitions definitions, List<Input> list) {
        for ( Input hi : list) {
            String nameString = hi.getName();
            QName typeRef = processType(hi.getType());
            InputData id = createInputData(nameString, typeRef);
            definitions.getDrgElement().add(id);
        }
    }

    private InputData createInputData(String nameString, QName typeRef) {
        InputData id = new TInputData();
        id.setName(nameString);
        id.setId("id_"+CodegenStringUtil.escapeIdentifier(nameString));
        InformationItem variable = new TInformationItem();
        variable.setName(nameString);
        variable.setId("idvar_"+CodegenStringUtil.escapeIdentifier(nameString));
        variable.setTypeRef(typeRef);
        id.setVariable(variable);
        return id;
    }

    private QName processType(String string) {
        switch(string) {
            case "string" : return new QName("string");
            case "number" : return new QName("number");
            case "boolean" : return new QName("boolean");
            default: return new QName("Any"); // TODO currently does not resolve external JSON Schemas
        }
    }

    private void enrichDefinitions(Definitions defs, String modelName) {
        setDefaultNSContext(defs);
        defs.setId("dmnid_" + modelName);
        defs.setName(modelName);
        String namespace = this.getClass().getPackage().getName().replaceAll("\\.", "_") + "_" + UUID.randomUUID();
        defs.setNamespace(namespace);
        defs.getNsContext().put(XMLConstants.DEFAULT_NS_PREFIX, namespace);
        defs.setExporter(this.getClass().getName());
    }

    private void setDefaultNSContext(Definitions definitions) {
        Map<String, String> nsContext = definitions.getNsContext();
        nsContext.put("feel", KieDMNModelInstrumentedBase.URI_FEEL);
        nsContext.put("dmn", KieDMNModelInstrumentedBase.URI_DMN);
        nsContext.put("dmndi", KieDMNModelInstrumentedBase.URI_DMNDI);
        nsContext.put("di", KieDMNModelInstrumentedBase.URI_DI);
        nsContext.put("dc", KieDMNModelInstrumentedBase.URI_DC);
    }
}
