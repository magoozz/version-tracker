public class Handler implements RequestHandler<TrackerData, TrackerData> {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String id = System.getenv("wiki_page_id");
    String base = System.getenv("base_url");
    String pt2 = System.getenv("end_url");
    String key = System.getenv("api_key");
    String user = System.getenv("user");
    final String s = user + ":" + key;
    final byte[] authBytes = s.getBytes(StandardCharsets.UTF_8);
    final String encoded = Base64.getEncoder().encodeToString(authBytes);
    String timeStamp = new SimpleDateFormat("yyyy/MM/dd-HH.mm.ss").format(new java.util.Date());
    String[][] foundation = new String[0][];
    List<List<String>> foundationList = new ArrayList<List<String>>();
    String[][] product = new String[0][];
    List<List<String>> productList = new ArrayList<List<String>>();
    Elements trs = null;
    String prettyS;
    StringBuilder updatePretty;
    String ver;
    String time;

    @SneakyThrows
    @Override
    @SerializedName("HTML")
    public TrackerData handleRequest(TrackerData event, Context context) {
        LambdaLogger logger = context.getLogger();
        // process event
        logger.log("PAYLOAD: " + gson.toJson(event));
        logger.log("EVENT TYPE: " + event.getClass().toString());

        //GET
        OkHttpClient client = new OkHttpClient().newBuilder().build();
        MediaType mediaType = MediaType.parse("text/plain");
        RequestBody body = RequestBody.create("", mediaType);
        Request request = new Request.Builder()
                .url(base + id + pt2)
                .method("GET", null)
                .addHeader("x-api-key", key)
                .addHeader("Authorization", "Basic " + encoded)
                .build();
        Response response = client.newCall(request).execute();

        //JSON TO HTML
        String string = (response.body().string());

        // increment the version number
        JSONObject versionCount = new JSONObject(string);
        JSONObject version = versionCount.getJSONObject("version");
        String conVersion = String.valueOf(version.getInt("number"));
        int filler = Integer.parseInt(conVersion) + 1;
        String num = String.valueOf(filler);
        versionCount.getJSONObject("version").put("number", num);
        string = string.replaceAll(conVersion + ",", num + ",");

        // derive the original table (jsonValue)
        String myJSON = (gson.toJson(string));
        String escape = StringEscapeUtils.unescapeJava(myJSON);
        String use = escape.substring(1);
        JSONObject json = new JSONObject(use);
        JSONObject jsonBody = json.getJSONObject("body").getJSONObject("storage");
        String jsonValue = jsonBody.getString("value");
        jsonValue = jsonValue.replaceAll("\"", "\\\\\"");

        //format the table correctly
        Document pretty = Jsoup.parseBodyFragment(jsonValue);
        prettyS = String.valueOf(pretty);
        prettyS = prettyS.replaceAll("&quot;", "\"").replaceAll("\"\"", "\"").replaceAll("=\"", "=");

        //CREATION OF THE TABLES
        Elements tables = pretty.select("table");
        for (Element table : tables) {
            trs = table.select("tr");

            //FOUNDATION TABLE
            foundation = new String[trs.size()][];
            for (int i = 0; i < trs.size(); i++) {
                Elements tds = trs.get(i).select("td");
                foundation[i] = new String[tds.size()];
                for (int j = 0; j < tds.size(); j++) {
                    foundation[i][j] = tds.get(j).text();
                }
            }
            //convert foundation into arrayList
            for (String[] item : foundation) {
                List<String> eachRecord = new ArrayList<String>();
                for (String value : item) {
                    eachRecord.add(String.valueOf(value));
                }
                foundationList.add(eachRecord);
            }

            //PRODUCT TABLE
            product = new String[trs.size()][];
            for (int i = 0; i < trs.size(); i++) {
                Elements ths = trs.get(i).select("th");
                product[i] = new String[ths.size()];
                for (int j = 0; j < ths.size(); j++) {
                    product[i][j] = ths.get(j).text();
                }
            }
            //convert products to arrayList
            for (String[] strings : product) {
                List<String> eachRecord = new ArrayList<String>();
                for (String value : strings) {
                    eachRecord.add(String.valueOf(value));
                }
                productList.add(eachRecord);
            }
        }

        updateElements(event);

        // clean up tables
        String toSplit = updatePretty.toString();
        toSplit = toSplit
                .replaceAll(">   <", "><")
                .replaceAll(">    <", "><")
                .replaceAll(">     <", "><");
        List<String> updatedTable = Arrays.asList(toSplit.split("<table"));
        String tb = "<table" + updatedTable.get(1);
        List<String> split = Arrays.asList(tb.split("table>"));
        String toEscape = split.get(0);
        toEscape = toEscape
                .replaceAll("h:\" ", "h: ")
                .replaceAll("\\\\><col style", "\\\\\" /><col style")
                .replaceAll("\\\\></colgroup>", "\\\\\" /></colgroup>")
                .replaceAll("<p></p>", "<p />")
                .replaceAll("</tbody>  </", "</tbody></table><p>* Products hosted on cloud. The others are on-prem.</p><p />");

        // put in the table and create the put command
        String escaped = escape.replace(jsonValue, toEscape);
        escaped = escaped.substring(1);
        escaped = StringUtils.chop(escaped);
        put(escaped, context);
        return event;
    }

    //Update line, or create new line. Then update the table to be PUT back to confluence
    public void updateElements(TrackerData event) {
        for (int i = 0; i < trs.size(); i++) {
            String compareP = String.join(",", productList.get(i));
            String compareF = String.join(",", foundationList.get(i));
            List<String> data = new ArrayList<>();
            String env = null;
            if (compareP.equals(event.getProductName())) {
                data = Arrays.asList(compareF.split(","));
                if (data == undefined || data.size() == 0) {
                    data = Arrays.asList("", "", "", "");
                    data.set(0, event.getEnvironment());
                    data.set(1, event.getComponentName());
                    data.set(2, event.getComponentVersion());
                    data.set(3, timeStamp);
                }
                env = data.get(0);
                String e = null;
                String flag = "off";
                int x;
                int pointer = 0;
                String[] eRow = env.split(" ");
                for (x = 0; x < eRow.length; x++) {
                    e = eRow[x];
                    if (eRow[x].equals(event.getEnvironment())) {
                        flag = "on";
                        pointer = x;
                    }
                }
                if (flag.equals("on")) {
                    String[] cRow = data.get(1).split(" ");
                    String[] vRow = data.get(2).split(" ");
                    String[] dRow = data.get(3).split(" ");
                    String component = cRow[pointer];
                    if (component.equals(event.getComponentName())) {
                        // updating a row
                        vRow[pointer] = event.getComponentVersion();
                        dRow[pointer] = timeStamp;
                        String upV = Arrays.toString(vRow);
                        String upD = Arrays.toString(dRow);
                        unArray(upV, upD);
                        data.set(2, ver);
                        data.set(3, time);
                    } else {
                        // new row
                        String zero = data.get(0);
                        String one = data.get(1);
                        String two = data.get(2);
                        String three = data.get(3);
                        data.set(0, zero + "</p><p>" + event.getEnvironment());
                        data.set(1, one + "</p><p>" + event.getComponentName());
                        data.set(2, two + "</p><p>" + event.getComponentVersion());
                        data.set(3, three + "</p><p>" + timeStamp);
                    }

                } else {
                    // new row
                    String zero = data.get(0);
                    String one = data.get(1);
                    String two = data.get(2);
                    String three = data.get(3);
                    data.set(0, zero + "</p><p>" + event.getEnvironment());
                    data.set(1, one + "</p><p>" + event.getComponentName());
                    data.set(2, two + "</p><p>" + event.getComponentVersion());
                    data.set(3, three + "</p><p>" + timeStamp);
                }
                updateTable(data, i);
            }
        }
    }

    public void unArray(String upV, String upD) {
        String use = upV.substring(1);
        String user = upD.substring(1);
        use = StringUtils.chop(use);
        user = StringUtils.chop(user);
        ver = use.replace(",", "");
        time = user.replace(",", "");
    }

    public void updateTable(List<String> data, int i) {
        foundationList.set(i, data);
        updatePretty = new StringBuilder();
        int b = 1;
        int m = -1;
        int counter = 0;
        Scanner in = null;
        in = new Scanner(prettyS);
        while (in.hasNext()) {
            String line = in.nextLine();
            if (line.contains("<td")) {
                m++;
                counter++;
                if (m == 4) {
                    m = 0;
                }
                if (counter == 5) {
                    counter = 1;
                    b++;
                }
                String temp = foundationList.get(b).get(m);
                temp = temp.replaceAll(" ", "</p><p>");
                line = "<td><p>" + temp + "</p></td>";
                updatePretty.append(line);
            } else {
                updatePretty.append(line);
            }
        }
    }

    //Performs the PUT
    public void put(String escaped, Context context) throws IOException {
        LambdaLogger logger = context.getLogger();
        OkHttpClient putClient = new OkHttpClient().newBuilder().build();
        MediaType JSON = MediaType.parse("application/json");
        RequestBody putBody = RequestBody.create(escaped, JSON);
        Request putRequest = new Request.Builder()
                .url(base + id + pt2)
                .method("PUT", putBody)
                .addHeader("x-api-key", key)
                .addHeader("Authorization", "Basic " + encoded)
                .addHeader("user-agent", "FoundationVersionTracker/1.0.0")
                .build();
        Response putResponse = putClient.newCall(putRequest).execute();

        if (!putResponse.isSuccessful()) {
            throw new IOException("Unexpected response code: " + putResponse);
        }
        logger.log(putResponse.body().string());
    }
}
