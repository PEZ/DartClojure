(ns dumch.parse-test
  (:require [clojure.test :refer :all]
            [dumch.improve :as improve :refer [simplify]]
            [dumch.parse :refer [clean widget-parser dart->clojure]]
            [instaparse.core :as insta]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]))

(deftest invocations-name-test
  (testing "simple constructor"
    (is (= (dart->clojure "Text('text')")
           (-> '(Text "text")))))
  (testing "typed constructor"
    (is (= (dart->clojure "Text<A<B>, C>('text')")
           (-> '(Text "text")))))
  (testing "line invocations"
    (is (= (dart->clojure "One(1).two().three(2, 3)")
           '(-> (One 1) (.two) (.three 2 3)))))
  (testing "instance method invocation"
    (is (= (dart->clojure "_pinPut.copyWith(border: 1)")
           '(.copyWith _pinPut :border 1))))
  (testing "field's field.method invocation"
    (is (= (dart->clojure "field!.pinPut.copyWith(border: 1)")
           '(-> field .pinPut (.copyWith :border 1)))))
  (testing "static field method invocation"
    (is (= (dart->clojure "SClass.field.copyWith(border: 1)")
           '(-> SClass .field (.copyWith :border 1)))))
  (testing "invocation on list"
    (is (= (-> "[1].cast()" dart->clojure)
           '(.cast [1])))
    (is (= (-> "[1].length" dart->clojure)
           '(.length [1]))))
  (testing "invocation on map"
    (is (= (-> "{1:1}.cast()" dart->clojure)
           '(.cast {1 1})))
    (is (= (-> "{1:1}.length" dart->clojure)
           '(.length {1 1}))))
  (testing "long chaing of dots"
    (is (= (-> "a.b().c.d().e()" dart->clojure)
           '(-> a (.b) .c (.d) (.e))))))

(deftest optional-name-test
  (testing "optional field somewhere in the chain"
    (is (= (-> "obj?.field.name?.first" dart->clojure)
           '(some-> obj .field .name .first)))
    (is (= (-> "obj.field?.name" dart->clojure)
           '(some-> obj .field .name))))
  (testing "field in optional object"
    (is (= (-> "obj?.name" dart->clojure)
           '(some-> obj .name))))
  (testing "only one optional in the chain"
    (is (= (-> "a.b?.c.d().e" dart->clojure)
           '(some-> a .b .c (.d) .e)))))

(deftest optional-invocation-test 
  (testing "optional field somewhere in the invocation chain"
    (is (= (dart->clojure "SClass?.field.copyWith(border: 1)")
           '(some-> SClass .field (.copyWith :border 1))))
    (is (= (dart->clojure "SClass.field?.copyWith()")
           '(some-> SClass .field (.copyWith)))))
  (testing "invocation on optional field"
    (is (= (dart->clojure "instance?.copyWith(border: 1)")
           '(some-> instance (.copyWith :border 1))))))

(deftest ^:current  list-test
  (testing "ignore spread operator"
    (is (= (-> "[...state.a.map((acc) => _t(ctx, acc))]" 
               dart->clojure)
           '[(-> :unidiomatic .a (.map (fn [acc] (_t ctx acc))))])))
  (testing "typed list"
    (is (= (-> "<Int>[1, 2]" dart->clojure)
           [1 2])))
  (testing "column children typeless list"
    (is 
      (= (-> "Column(children: [const Text('name'),
                                Icon(Icons.widgets)])" 
             dart->clojure)
         '(Column :children [(Text "name") (Icon (.widgets Icons))]))))
  (testing "ifnull and ternary list elements"
    (is (= (-> "[ a? 1 : b, c ?? d ]" dart->clojure)
           '[(if a 1 b) (or c d)]))))

(deftest map-test
  (testing "typeless map as named parameter"
    (is 
      (= (dart->clojure "ListCell.icon(m: {1 : 2, 'a' : b, c : 'd'})")
         '(.icon ListCell :m {1 2, "a" b, c "d"}))))
  (testing "typed map" 
    (is 
      (= (-> "<int, List<int>>{1: [1, 2]}" dart->clojure)
         {1 [1 2]})))
  (testing "ifnull and ternary map elements" 
    (is 
      (= (-> "<int, List<int>>{ a ? 1 : 2 : a ?? b}" dart->clojure)
         '{(if a 1 2) (or a b)}))))

(deftest set!-test
  (is (= (dart->clojure "a = b") :unidiomatic)))

(deftest get-test
  (testing "single get"
   (is (= (dart->clojure "tabs[2]") '(tabs 2))))
  (testing "serveral get in a row"
    (is (= (dart->clojure "questions[1]['two']") 
           '((questions 1) "two")))))

(deftest logic-test
  (testing "and: &&"
    (is (= (dart->clojure "b && a") '(and b a))))
  (testing "or: ||, ??"
      (is (= (dart->clojure "b || a") '(or b a)))
      (is (= (dart->clojure "b ?? a") '(or b a))))
  (testing "compare: >, <, =>, <="
      (is (= (dart->clojure "b > a") '(> b a)))
      (is (= (dart->clojure "b < a") '(< b a)))
      (is (= (dart->clojure "b >= a") '(>= b a)))
      (is (= (dart->clojure "b <= a") '(<= b a)))
      (is (= (dart->clojure "1 is int") '(dart/is? 1 int))))
  (testing "equality: ==, !="
      (is (= (dart->clojure "b == a") '(= b a)))
      (is (= (dart->clojure "b != a") '(not= b a)))))

(deftest ternary-test
  (testing "simple ternary" 
    (is (= (dart->clojure "a ? b : c") '(if a b c))))
  (testing "lambda as ternary parameter"
    (is (= (dart->clojure 
             "TextButton(onPressed: _searchEnabled ? () { pop(); } : null)")
           '(TextButton
              :onPressed
              (if _searchEnabled 
                (fn [] (pop))
                null))))))

(deftest unary-prefix-test
  (testing "not" (is (= (dart->clojure "!a") '(not a))))
  (testing "inc" (is (= (dart->clojure "++a") '(inc a))))
  (testing "dec" (is (= (dart->clojure "--a") '(dec a))))
  (testing "-" (is (= (dart->clojure "-a") '(- a)))))

(deftest math-test
  (testing "difference"
    (is (= (dart->clojure "b - a") '(- b a))))
  (testing "sum"
    (is (= (dart->clojure "b + a") '(+ b a))))
  (testing "remainder"
    (is (= (dart->clojure "b % a") '(rem b a))))
  (testing "divide and round"
    (is (= (dart->clojure "b ~/ a") '(quot b a))))
  (testing "division"
    (is (= (dart->clojure "b / a") '(/ b a))))
  (testing "multiply"
    (is (= (dart->clojure "b * a") '(* b a)))))

(deftest lambda-test
  (testing "lambda with =>"
    (is (= (dart->clojure "Button(onPressed: (ctx) => 1 + 1;)")
           '(Button :onPressed (fn [ctx] (+ 1 1))))))
  (testing "lambda with body"
    (is (= (dart->clojure "Button(onPressed: (ctx) { println(a == a); setState(a); })")
           '(Button :onPressed (fn [ctx] (do (println (= a a)) (setState a)))))))
  (testing "lambda with typed argument"
    (is (= (dart->clojure "Button(onPressed: (Context ctx) => 1 + 1;)")
           '(Button :onPressed (fn [ctx] (+ 1 1)))))))

(deftest if-test
  (testing "one branch if with curly body"
    (is (= (dart->clojure "if (a == b) print('a');")
           '(when (= a b) (print "a")))))
  (testing "if else with and without body"
    (is (= (dart->clojure "if (b) { print(1); return 3; } else return 2;")
           '(if b (do (print 1) 3) 2))))
  (testing "if and else-if"
    (is (= (dart->clojure "if (b) b; else if (a) a; else if (c) c;")
           '(cond b b a a c c))))
  (testing "if, and else-if, and else"
    (is (= (dart->clojure "if (b) b; else if (a) a; else c;")
           '(cond b b a a :else c)))))

(deftest comments-test
  (testing "line comment"
    (is (= (dart->clojure "Text(
                             'name', // comment
                           )")
           '(Text "name"))))
  (testing "block comment"
    (is (= (dart->clojure "Text(widget.photo.user!.name /* comment */ )")
           '(Text (-> widget .photo .user .name)))))
  (testing "comment-like structure inside string"
    (is (= (dart->clojure "Text(
                                'http://looks-like-comment', 
                                '/* one more */'
                                )")
           '(Text "http://looks-like-comment" "/* one more */")))))

(deftest strings-test
  (testing "special symbols inside string test"
    (is 
      (= 1 
         (->> "'\n\t\\s\"'"
              clean
              (insta/parses widget-parser) 
              count))))
  (testing "multiline string with inner comments" 
    (is 
      (= 1 
         (->>
           "\"\"\"
           multiline // comment like 
           \" some string inside multiline string \"
           /* another comment */ \"\"\""
           clean
           (insta/parses widget-parser) 
           count)))))

(deftest assignment-test
  (testing "typeless var assignment"
    (is (= (dart->clojure  "var a = b") :unidiomatic)))
  (testing "final assignment"
    (is (= (dart->clojure  "final String s = '1'; ") :unidiomatic)))
  (testing "const assignment"
    (is (= (dart->clojure  "const bar = 1000000") :unidiomatic))))

(deftest await-test
  (is (= (dart->clojure  "await a") '(await a))))

(deftest statement-test
  (testing "several expressions | statements in a row with | without ';'"
    (is (= (dart->clojure  "a = 2;
                            if (item.isLoading) {
                               print(1)
                            }
                            a") 
           '(do :unidiomatic (when (.isLoading item) (print 1)) a)))))

(deftest flatten-same-operators
  (testing "calculation results"
    (is (-> "(2 + 1 + 1 == 4 && true) && (1 == 1 || false) && 3 == 3"
            dart->clojure
            n/string 
            read-string
            eval)))
  (testing "+, *, and, or"
      (doseq [sym ["+" "*" "||" "&&"]]
        (is (= (-> (str 1 sym 2 sym 3 sym 4 sym 5) dart->clojure)
               `(~(symbol (case sym "||" 'or, "&&" 'and sym)) 1 2 3 4 5)))))
  (testing "flatten compare operators when possible"
      (is (= (-> "3 > 2 && 2 > 1 && true" dart->clojure n/string)
             "(and true (> 3 2 1))"))
      (is (= (-> "4 >= 3 && 3 >= 2 || 2 >= 1" dart->clojure)
             '(and (>= 4 3) (or (>= 3 2) (>= 2 1)))))
      (is (= (-> "5 >= 4 && 4 >= 3 && 3 >= 2 || 2 >= 1" dart->clojure)
             '(and (or (>= 3 2) (>= 2 1)) (>= 5 4 3))))))
